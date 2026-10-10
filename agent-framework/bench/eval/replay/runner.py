"""回放 / 联机评测执行编排（flywheel.py replay|live 子命令与 eval-studio 共用入口）。

replay（离线回放，设计 §4）：
  evalpack → 起本地 replay-llm（子进程）→ 驱动被测服务逐轮回放录制输入
  → A1 确定性断言（checks）+ A2 轨迹等价（replayer 匹配统计）+ A3 漂移率
  → 可选 LLM judge → report.json / report.html

live（联机链路评测，设计 §6.2 live 模式）：
  用例输入直打真实服务地址（目标档案 base_url）→ A1 断言 + 可选 judge → 同构报告。

exit code 契约与飞轮一致：0 全过 / 1 有失败 / 2 无可执行用例。
"""

import asyncio
import html
import json
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import httpx

EVAL_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(EVAL_DIR))

from executor import checks as checks_mod  # noqa: E402
from executor import sse_client  # noqa: E402
from replay import trajectory as traj_mod  # noqa: E402
from replay.packager import verify_checksums  # noqa: E402

REPLAY_LLM_MJS = EVAL_DIR / "mock" / "replay-llm.mjs"
REPLAY_HTTP_MJS = EVAL_DIR / "mock" / "replay-http.mjs"


class RunnerError(Exception):
    pass


# ---------------------------------------------------------------------------
# 回放器子进程管理（LLM 主链路 + 沙箱/MCP 按包内交互按需拉起）
# ---------------------------------------------------------------------------

def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _wait_proc_health(proc: subprocess.Popen, url: str, name: str, timeout_s: float = 15.0) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if proc.poll() is not None:
            out = proc.stdout.read() if proc.stdout else ""
            raise RunnerError(f"{name} 启动失败: {out[-400:]}")
        try:
            if httpx.get(url, timeout=1.0).status_code == 200:
                return
        except httpx.HTTPError:
            time.sleep(0.2)
    proc.kill()
    raise RunnerError(f"{name} 健康检查超时")


def start_replay_llm(pack_dir: str, rewrites: list[str] | None = None) -> tuple[subprocess.Popen, int]:
    port = free_port()
    cmd = ["node", str(REPLAY_LLM_MJS), "--pack", pack_dir, "--port", str(port)]
    for r in rewrites or []:
        cmd += ["--rewrite", r]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    _wait_proc_health(proc, f"http://127.0.0.1:{port}/healthz", "replay-llm")
    return proc, port


def start_replay_http(pack_dir: str, kind: str) -> tuple[subprocess.Popen, int] | None:
    """包内含该 kind 录制交互时拉起对应回放器，否则返回 None（空目录不算——
    否则 provision 会误开 SANDBOX_ENABLED 并把上游指向一个无录制件的回放器）。"""
    kind_dir = Path(pack_dir) / "interactions" / kind
    if not kind_dir.exists() or not any(kind_dir.glob("*.json")):
        return None
    port = free_port()
    proc = subprocess.Popen(["node", str(REPLAY_HTTP_MJS), "--pack", pack_dir,
                             "--kind", kind, "--port", str(port)],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    _wait_proc_health(proc, f"http://127.0.0.1:{port}/healthz", f"replay-{kind}")
    return proc, port


def start_replayers(pack_dir: str, rewrites: list[str] | None = None) -> dict[str, tuple]:
    """一次拉起全部回放器：{"llm": (proc, port), "sandbox": (...), "mcp": (...)}。

    供 provision/被测服务注入上游地址使用；run_replay(replayers=...) 接管生命周期。
    """
    out: dict[str, tuple] = {}
    proc, port = start_replay_llm(pack_dir, rewrites)
    out["llm"] = (proc, port)
    for kind in ("sandbox", "mcp"):
        started = start_replay_http(pack_dir, kind)
        if started:
            out[kind] = started
    return out


def stop_replayers(replayers: dict[str, tuple]) -> None:
    for proc, _port in replayers.values():
        if proc and proc.poll() is None:
            proc.terminate()


def replayer_stats(port: int) -> dict[str, Any]:
    with httpx.Client(timeout=5.0) as c:
        return c.get(f"http://127.0.0.1:{port}/stats").json()


# ---------------------------------------------------------------------------
# evalpack / 用例加载
# ---------------------------------------------------------------------------

def load_pack(pack_dir: str) -> dict[str, Any]:
    root = Path(pack_dir)
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("format_version") != 1:
        raise RunnerError(f"evalpack format_version 不支持: {manifest.get('format_version')}")
    if not verify_checksums(pack_dir):
        raise RunnerError("evalpack CHECKSUMS 校验失败（包被篡改或不完整）")
    return manifest


def load_pack_cases(pack_dir: str, case_ids: list[str] | None = None) -> list[dict[str, Any]]:
    cases = []
    for p in sorted((Path(pack_dir) / "cases-draft").glob("*.json")):
        case = json.loads(p.read_text(encoding="utf-8"))
        if case.get("status") == "retired":
            continue
        cases.append(case)
    if case_ids:
        cases = [c for c in cases if c["case_id"] in set(case_ids)]
    return cases


def load_session(pack_dir: str, sid: str) -> dict[str, Any]:
    return json.loads((Path(pack_dir) / "sessions" / f"{sid}.json").read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# 用例驱动（录制输入逐轮回放 + HITL 决策回放）
# ---------------------------------------------------------------------------

async def drive_case(base_url: str, case: dict[str, Any], session: dict[str, Any] | None,
                     timeout_s: float = 120.0) -> dict[str, Any]:
    """按录制轮次驱动被测服务；返回统一轨迹结构（与趋势轨 trace 同形）。"""
    sid = case.get("input", {}).get("sessionId") or (session or {}).get("sid") or f"drv-{int(time.time()*1000)}"
    turns = (session or {}).get("turns") or [{"input": case["input"].get("message", "")}]
    events: list[dict[str, Any]] = []
    status = "success"
    error_info: str | None = None
    t0 = time.monotonic()
    try:
        for i, turn in enumerate(turns):
            if i == 0:
                payload = {"message": turn.get("input", ""), "userId": case["input"].get("userId", "eval-replay"),
                           "sessionId": sid}
                _, evs = await sse_client.stream_chat(base_url, payload, timeout_s=timeout_s)
                events.extend(evs)
            else:
                # 后续轮：继续同一会话（sessionId 已建，直接发消息）
                _, evs = await sse_client.stream_chat(base_url, {"message": turn.get("input", ""),
                                                                 "userId": case["input"].get("userId", "eval-replay"),
                                                                 "sessionId": sid}, timeout_s=timeout_s)
                base_ms = events[-1]["t_ms"] if events else 0
                events.extend([{**e, "t_ms": e["t_ms"] + base_ms} for e in evs])
            # HITL：出现挂起且录制件带决策 → 回放确认续段（设计 §4.1 第 2 步）
            view_partial = sse_client.build_view(events)
            if view_partial["hitl"]["pending"] and session and session.get("hitl", {}).get("decisions"):
                asks = view_partial["hitl"]["asks"][-1]
                results = [{"tool_call_id": tc["tool_call_id"],
                            "confirmed": session["hitl"]["decisions"].get(tc["tool_call_id"],
                            session["hitl"]["decisions"].get(tc.get("name"), True))}
                           for tc in asks.get("tool_calls") or []]
                base_ms = events[-1]["t_ms"] if events else 0
                evs = await sse_client.stream_confirm(base_url, sid, results,
                                                      t_offset_ms=base_ms)
                events.extend(evs)
    except sse_client.SSEError as e:
        status = "failed"
        error_info = str(e)
    view = sse_client.build_view(events)
    return {
        "case_id": case["case_id"], "session_id": sid, "repeat_no": 1,
        "status": status, "duration_ms": int((time.monotonic() - t0) * 1000),
        "events": events, "view": view, "error_info": error_info,
    }


# ---------------------------------------------------------------------------
# judge / 报告
# ---------------------------------------------------------------------------

async def judge_case(case: dict[str, Any], trace: dict[str, Any],
                     cfg: dict[str, str]) -> dict[str, Any]:
    from graders.correctness import judge_correctness
    return await judge_correctness(case, trace, cfg)


REPORT_TMPL = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<title>eval run {run_id}</title>
<style>
 body{{margin:0;background:#0f1419;color:#dbe4ee;font:14px/1.6 -apple-system,'PingFang SC',sans-serif}}
 main{{max-width:1000px;margin:0 auto;padding:24px}}
 .card{{background:#1a2129;border:1px solid #2d3947;border-radius:10px;padding:16px 20px;margin-bottom:16px}}
 h1{{font-size:18px}} h2{{font-size:15px}} table{{width:100%;border-collapse:collapse;font-size:13px}}
 th,td{{text-align:left;padding:6px 10px;border-bottom:1px solid #2d3947}}
 .ok{{color:#3fb96f}}.err{{color:#e06c5c}}.warn{{color:#e0a83c}}.dim{{color:#8496a9}}
 pre{{background:#12181f;border-radius:6px;padding:10px;overflow:auto;font-size:12px}}
</style></head><body><main>
<h1>评测报告 <span class="dim">{run_id}</span></h1>
<div class="card"><h2>总览</h2>
<p>类型 <b>{type}</b> · 数据包 <b>{pack_id}</b> · 模式 <b>{mode}</b> · judge <b>{judge_model}</b></p>
<p>用例 <b>{case_total}</b> · 通过 <b class="ok">{passed}</b> · 失败 <b class="err">{failed}</b> ·
通过率 <b>{pass_rate}</b> · 漂移率 <b>{drift_rate}</b>{judge_avg}</p></div>
<div class="card"><h2>用例明细</h2>
<table><tr><th>用例</th><th>状态</th><th>耗时</th><th>检查项</th><th>轨迹</th><th>judge</th></tr>{rows}</table></div>
<div class="card"><h2>原始数据</h2><pre>{raw}</pre></div>
</main></body></html>"""


def render_report_html(report: dict[str, Any]) -> str:
    # 报告插值点（case_id/检查项名/模型与录制产出的文本等）可能携带任意内容，error/judge 理由
    # 经 raw JSON 块入页；所有文本插值统一 html.escape，阻断 report.html 注入
    rows = []
    for c in report["cases"]:
        checks = " ".join(
            f'<span class="{"ok" if ch["passed"] else "err"}">{html.escape(str(ch["name"]))}</span>'
            for ch in (c.get("checks") or [])) or '<span class="dim">-</span>'
        score = (c.get("scores") or {}).get("correctness")
        score_str = "-" if not score else f"{score['score']:.2f}"
        traj = c.get("trajectory") or {}
        rows.append(
            f"<tr><td>{html.escape(str(c['case_id']))}</td>"
            f"<td class=\"{'ok' if c['status']=='passed' else 'err'}\">{html.escape(str(c['status']))}</td>"
            f"<td>{c.get('duration_ms','-')}ms</td><td>{checks}</td>"
            f"<td class=\"dim\">{html.escape(str(traj.get('step_status','-')))} "
            f"(drift {traj.get('drift_rate','-')})</td>"
            f"<td>{score_str}</td></tr>")
    s = report["summary"]
    judge_avg = f" · judge均分 <b>{s['score_avg']:.2f}</b>" if s.get("score_avg") is not None else ""
    return REPORT_TMPL.format(
        run_id=html.escape(str(report["run_id"])), type=html.escape(str(report["type"])),
        pack_id=html.escape(str(report.get("pack_id", "-"))),
        mode=html.escape(str(report.get("mode", "-"))),
        judge_model=html.escape(str(report.get("judge", {}).get("model") or "-")),
        case_total=s["case_total"], passed=s["pass"], failed=s["fail"],
        pass_rate=f"{s['pass_rate']:.0%}", drift_rate=s.get("drift_rate", 0),
        judge_avg=judge_avg, rows="".join(rows),
        raw=html.escape(json.dumps(report, ensure_ascii=False)[:6000]))


def build_report(run_id: str, run_type: str, pack_id: str | None, mode: str,
                 cases_result: list[dict[str, Any]], trajectories: list[dict[str, Any]],
                 judge_cfg: dict[str, str] | None) -> dict[str, Any]:
    passed = sum(1 for c in cases_result if c["status"] == "passed")
    failed = sum(1 for c in cases_result if c["status"] == "failed")
    errored = sum(1 for c in cases_result if c["status"] == "error")
    total = len(cases_result)
    scores = [c["scores"]["correctness"]["score"] for c in cases_result
              if (c.get("scores") or {}).get("correctness")]
    return {
        "run_id": run_id, "type": run_type, "pack_id": pack_id, "mode": mode,
        "judge": {"model": (judge_cfg or {}).get("model"), "skipped": judge_cfg is None},
        "summary": {
            "case_total": total, "pass": passed, "fail": failed, "error": errored,
            "pass_rate": round(passed / total, 4) if total else 0.0,
            "score_avg": round(sum(scores) / len(scores), 4) if scores else None,
            "drift_rate": traj_mod.run_drift_rate(trajectories),
            "duration_ms_total": sum(c.get("duration_ms") or 0 for c in cases_result),
        },
        "cases": cases_result,
    }


# ---------------------------------------------------------------------------
# replay / live 主入口
# ---------------------------------------------------------------------------

async def run_replay(pack_dir: str, out_dir: str, base_url: str,
                     mode: str = "strict", case_ids: list[str] | None = None,
                     judge_cfg: dict[str, str] | None = None,
                     rewrites: list[str] | None = None,
                     keep_replayer: bool = False,
                     replayer: tuple[subprocess.Popen, int] | None = None,
                     replayers: dict[str, tuple] | None = None) -> int:
    """离线回放主流程。被测服务须已在 base_url 运行（其 LLM/沙箱/MCP 上游指向回放器）。

    replayer：外部注入的 replay-llm (proc, port)——仅 LLM 单回放器旧形态；
    replayers：start_replayers() 的完整多回放器字典（推荐，provision/studio 使用），
    接管全部回放器生命周期。两者都缺省时内部只拉起 replay-llm。
    """
    manifest = load_pack(pack_dir)
    cases = load_pack_cases(pack_dir, case_ids)
    if not cases:
        print("[replay] 无可执行用例")
        return 2
    run_id = f"replay-{time.strftime('%Y%m%d-%H%M%S')}-{manifest['pack_id'][:24]}"
    out = Path(out_dir)
    (out / "cases").mkdir(parents=True, exist_ok=True)

    owned: dict[str, tuple] = {}
    if replayers is not None:
        proc, llm_port = replayers["llm"]
    elif replayer is not None:
        proc, llm_port = replayer
    else:
        proc, llm_port = start_replay_llm(pack_dir, rewrites)
        owned["llm"] = (proc, llm_port)
    print(f"[replay] replay-llm :{llm_port}（用例 {len(cases)} 条，mode={mode}）")
    try:
        cases_result: list[dict[str, Any]] = []
        trajectories: list[dict[str, Any]] = []
        for case in cases:
            sid = case.get("source", {}).get("sid") or case["case_id"]
            session = load_session(pack_dir, sid)
            trace = await drive_case(base_url, case, session)
            check_results = checks_mod.evaluate(case.get("expected") or {}, trace["view"])
            checks_passed = all(c["passed"] for c in check_results)
            expected_calls = len(session.get("llm_calls") or [])
            stats = replayer_stats(llm_port)
            trajectory = traj_mod.evaluate_trajectory(sid, stats, expected_calls)
            status = "passed" if (trace["status"] == "success" and checks_passed) else "failed"
            if status == "passed" and mode == "strict" and trajectory["step_status"] != "pass":
                status = "failed"
            scores = None
            if judge_cfg:
                try:
                    scores = {"correctness": await judge_case(case, trace, judge_cfg)}
                except Exception as e:  # judge 单项失败只降级（advisory 永不阻断）
                    scores = {"correctness": {"score": 0.0, "reason": f"judge 不可用: {e}"}}
                    case["_judge_skipped"] = True
            result = {
                "case_id": case["case_id"], "sid": sid, "status": status,
                "duration_ms": trace["duration_ms"],
                "checks": check_results, "trajectory": trajectory, "scores": scores,
                "error_info": trace["error_info"],
                "final_output": trace["view"]["final_output"],
                "recorded_final": session.get("recorded_final"),
            }
            cases_result.append(result)
            trajectories.append(trajectory)
            mark = "PASS" if status == "passed" else "FAIL"
            print(f"  [{mark}] {case['case_id']} checks={checks_passed} "
                  f"traj={trajectory['step_status']} drift={trajectory['drift_rate']}")
            # 落 trace / trajectory（结果路径可寻址，设计 §4.4）
            with open(out / "cases" / f"{case['case_id']}.trace.json", "w", encoding="utf-8") as f:
                json.dump(trace, f, ensure_ascii=False, indent=1)
            with open(out / "cases" / f"{case['case_id']}.trajectory.json", "w", encoding="utf-8") as f:
                json.dump(trajectory, f, ensure_ascii=False, indent=1)

        stats = replayer_stats(llm_port)
        all_stats: dict[str, Any] = {"llm": stats}
        for kind in ("sandbox", "mcp"):
            if (replayers or owned).get(kind):
                all_stats[kind] = replayer_stats((replayers or owned)[kind][1])
        with open(out / "replayer-logs.json", "w", encoding="utf-8") as f:
            json.dump(all_stats, f, ensure_ascii=False, indent=1)
        report = build_report(run_id, "replay", manifest["pack_id"], mode,
                              cases_result, trajectories, judge_cfg)
        report["replayers"] = {k: {kk: vv for kk, vv in s.items() if kk != "sessions"}
                               for k, s in all_stats.items()}
        _write_report(out, report)
        failed = report["summary"]["fail"] + report["summary"]["error"]
        print(f"[replay] 通过 {report['summary']['pass']}/{report['summary']['case_total']}；"
              f"报告: {out}/report.json")
        return 1 if failed else 0
    finally:
        for p, _port in owned.values():
            if not keep_replayer and p.poll() is None:
                p.terminate()


async def run_live(base_url: str, out_dir: str, pack_dir: str | None = None,
                   cases_dir: str | None = None, case_ids: list[str] | None = None,
                   judge_cfg: dict[str, str] | None = None,
                   timeout_s: float = 120.0) -> int:
    """联机链路评测：用例输入直打真实服务（档案 live.base_url），A1 断言 + judge。"""
    if pack_dir:
        load_pack(pack_dir)
        cases = load_pack_cases(pack_dir, case_ids)
    elif cases_dir:
        cases = []
        for p in sorted(Path(cases_dir).glob("*.json")):
            c = json.loads(p.read_text(encoding="utf-8"))
            if c.get("status") != "retired":
                cases.append(c)
        if case_ids:
            cases = [c for c in cases if c["case_id"] in set(case_ids)]
    else:
        raise RunnerError("live 模式需要 --pack 或 --cases 提供用例")
    if not cases:
        print("[live] 无可执行用例")
        return 2
    run_id = f"live-{time.strftime('%Y%m%d-%H%M%S')}"
    out = Path(out_dir)
    (out / "cases").mkdir(parents=True, exist_ok=True)
    print(f"[live] 被测 {base_url}（用例 {len(cases)} 条）")
    cases_result: list[dict[str, Any]] = []
    for case in cases:
        trace = await drive_case(base_url, case, None, timeout_s=timeout_s)
        # live 语义（设计 §6.2）：真实模型非确定，文本包含断言剥离（质量交给 judge），
        # 结构层断言（帧/工具/无 error）保留
        live_expected = {k: v for k, v in (case.get("expected") or {}).items()
                         if k != "final_text_contains"}
        check_results = checks_mod.evaluate(live_expected, trace["view"])
        checks_passed = all(c["passed"] for c in check_results)
        status = "passed" if (trace["status"] == "success" and checks_passed) else "failed"
        scores = None
        if judge_cfg:
            try:
                scores = {"correctness": await judge_case(case, trace, judge_cfg)}
            except Exception as e:
                scores = {"correctness": {"score": 0.0, "reason": f"judge 不可用: {e}"}}
        cases_result.append({
            "case_id": case["case_id"], "sid": trace["session_id"], "status": status,
            "duration_ms": trace["duration_ms"], "checks": check_results,
            "trajectory": None, "scores": scores, "error_info": trace["error_info"],
            "final_output": trace["view"]["final_output"],
        })
        mark = "PASS" if status == "passed" else "FAIL"
        print(f"  [{mark}] {case['case_id']} checks={checks_passed} "
              f"{trace['error_info'] or ''}")
        with open(out / "cases" / f"{case['case_id']}.trace.json", "w", encoding="utf-8") as f:
            json.dump(trace, f, ensure_ascii=False, indent=1)
    pack_id = None
    if pack_dir:
        pack_id = json.loads((Path(pack_dir) / "manifest.json").read_text(encoding="utf-8"))["pack_id"]
    report = build_report(run_id, "live", pack_id, "-", cases_result, [], judge_cfg)
    _write_report(out, report)
    failed = report["summary"]["fail"] + report["summary"]["error"]
    print(f"[live] 通过 {report['summary']['pass']}/{report['summary']['case_total']}；报告: {out}/report.json")
    return 1 if failed else 0


def _write_report(out: Path, report: dict[str, Any]) -> None:
    with open(out / "report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    (out / "report.html").write_text(render_report_html(report), encoding="utf-8")
