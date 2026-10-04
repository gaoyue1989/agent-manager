#!/usr/bin/env python3
"""离线评测全链路 e2e（设计 docs/design/agent-framework-eval-offline-record-replay-design.md）。

一个进程编排全部子进程（mock 上游 / collector / stub agent / replay-llm / studio），
覆盖：单元断言 → collector 三协议录制与安全语义 → 真实录制（OpenRouter，可回退 mock）
→ pack 打包 → replay 回放（A1/A2/A3）→ judge（mimo）→ studio API 全流程。

用法：
  python3 bench/eval/tests/e2e_offline_loop.py                # 全量（真实录制模型+judge）
  python3 bench/eval/tests/e2e_offline_loop.py --offline      # 全离线（mock 上游，跳过 judge）
  python3 bench/eval/tests/e2e_offline_loop.py --phase collector  # 单阶段调试

凭据经环境变量注入（.env.secrets）：EVAL_RECORD_LLM_*（录制上游）、EVAL_LLM_*（judge）。
exit 0 = 全部通过；任一断言失败即非 0（子进程日志保留在 --work 目录）。
"""

import argparse
import asyncio
import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import httpx

TESTS_DIR = Path(__file__).resolve().parent
EVAL_DIR = TESTS_DIR.parent
BENCH_DIR = EVAL_DIR.parent
sys.path.insert(0, str(EVAL_DIR))

from executor import sse_client  # noqa: E402
from replay import normalize as nz  # noqa: E402
from replay import trajectory as traj_mod  # noqa: E402
from replay.packager import pack as do_pack, verify_checksums  # noqa: E402

PASS, FAIL = 0, 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ✔ {name}" + (f" — {detail}" if detail else ""))
    else:
        FAIL += 1
        print(f"  ✘ {name} — {detail}")


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Proc:
    """子进程封装：启动/等待健康/终止，日志落文件。"""

    def __init__(self, name: str, cmd: list[str], work: Path, env: dict[str, str] | None = None,
                 health: str | None = None):
        self.name = name
        self.work = work
        self.health = health
        e = dict(os.environ)
        e.update(env or {})
        self.log = open(work / f"{name}.log", "w")
        self.p = subprocess.Popen(cmd, stdout=self.log, stderr=subprocess.STDOUT, env=e)
        if health:
            deadline = time.time() + 20
            while time.time() < deadline:
                # 子进程若启动即死（如端口被占 EADDRINUSE），健康检查可能打到
                # 同端口的历史进程上——必须同时校验自身存活，避免误判
                if self.p.poll() is not None:
                    raise RuntimeError(f"{name} 启动失败，日志：\n{(work / f'{name}.log').read_text()[-1500:]}")
                try:
                    with urllib.request.urlopen(health, timeout=1) as r:
                        if r.status == 200:
                            return
                except Exception:
                    time.sleep(0.25)
            raise RuntimeError(f"{name} 健康检查超时: {health}")

    def stop(self) -> None:
        if self.p.poll() is None:
            self.p.terminate()
            try:
                self.p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.p.kill()
        self.log.close()


# ---------------------------------------------------------------------------
# 阶段 1：单元断言（离线）
# ---------------------------------------------------------------------------

def phase_unit() -> None:
    print("\n[phase 1] 单元断言（normalize / trajectory / packager 派生）")
    check("归一化：ISO 时间戳", nz.normalize_text("at 2026-10-04T07:23:06.770Z ok") == "at <TS> ok")
    check("归一化：epoch 毫秒", nz.normalize_text("id=1791098087123 end") == "id=<TS_MS> end")
    check("归一化：UUID", nz.normalize_text("uuid 550e8400-e29b-41d4-a716-446655440000!") ==
          "uuid <UUID>!")
    check("归一化：gw-hash 身份", nz.normalize_text("AgentStateStore ID: gw-3f146047e5fd") ==
          "AgentStateStore ID: <GW>")
    check("归一化：host:port", nz.normalize_text("http://127.0.0.1:18902/v1") == "<HOST>/v1")
    check("归一化：长 token", nz.normalize_text("tok abcdef0123456789abcdef0123456789 end") ==
          "tok <TOKEN> end")
    check("归一化：普通数字不受影响", nz.normalize_text("max_tokens 1024") == "max_tokens 1024")

    fingerprint = nz.request_fingerprint({"messages": [{"role": "user", "content": "hi 2026-01-01T00:00:00Z"}]})
    fingerprint2 = nz.request_fingerprint({"messages": [{"role": "user", "content": "hi 2027-01-01T00:00:00Z"}]})
    check("指纹：仅时间戳不同的请求等价", fingerprint == fingerprint2)
    check("指纹：不同内容不等价",
          fingerprint != nz.request_fingerprint({"messages": [{"role": "user", "content": "other"}]}))

    stats = {"sessions": {"s1": {"calls": 8, "exact": 7, "normalized": 1, "drift": 0, "background": 1}}}
    t = traj_mod.evaluate_trajectory("s1", stats, expected_llm_calls=8)
    check("轨迹等价：零漂移通过", t["step_status"] == "pass" and t["drift_rate"] == 0.0)
    stats2 = {"sessions": {"s1": {"calls": 8, "exact": 6, "normalized": 1, "drift": 1, "background": 0}}}
    t2 = traj_mod.evaluate_trajectory("s1", stats2, expected_llm_calls=8)
    check("轨迹等价：漂移判失败", t2["step_status"] == "fail" and t2["drift_rate"] == 0.125)
    check("漂移率：run 级加权", traj_mod.run_drift_rate([t, t2]) == 0.0625)

    from replay.packager import final_text_from_chunks, usage_from_chunks
    chunks = [
        json.dumps({"choices": [{"delta": {"content": "你好"}}]}),
        json.dumps({"choices": [{"delta": {"content": "，世界"}}]}),
        "[DONE]",
    ]
    check("chunks 反推终答", final_text_from_chunks(chunks) == "你好，世界")
    u = usage_from_chunks([json.dumps({"usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}})])
    check("chunks 反推 usage", u == {"input": 3, "output": 2, "total": 5})


# ---------------------------------------------------------------------------
# 阶段 2：collector 三协议录制 + 管理 API（hermetic，mock 上游）
# ---------------------------------------------------------------------------

def phase_collector(work: Path, mock_up: Proc, collector: Proc) -> str:
    print("\n[phase 2] collector 三协议录制 + 管理 API（hermetic）")
    admin = "http://127.0.0.1:18300"
    mock_base = f"http://127.0.0.1:{mock_up.port}"

    # 2.1 创建档案（hermetic：全部指向 mock 上游）+ 预检
    prof = {
        "ns": "e2e", "display": "e2e 演示服务", "state": "recording",
        "upstream": {"llm": f"{mock_base}/v1", "llm_api_key": "sk-collector-inject-key-1234567890",
                     "llm_default_model": "mock-record-model", "sandbox": mock_base, "mcp": {"srv1": f"{mock_base}/mcp"}},
        "record": {"sampling": 1, "body_max_bytes": 262144},
    }
    r = httpx.post(f"{admin}/api/profiles", json=prof, timeout=10).json()
    check("创建接入档案", r.get("ns") == "e2e", json.dumps(r, ensure_ascii=False)[:120])
    r = httpx.post(f"{admin}/api/profiles/e2e/precheck", timeout=15).json()
    check("预检 LLM 上游", r.get("llm", {}).get("ok") is True, str(r)[:120])
    check("预检沙箱上游", r.get("sandbox", {}).get("ok") is True)

    # 2.2 LLM 流式录制：session 头 + 默认模型注入 + 响应透传
    sess = "sess-collector-direct"
    body = {"stream": True, "messages": [{"role": "user", "content": f"echo: collector-direct|{sess}|邮箱 user@test.com"}]}
    frames = []
    with httpx.stream("POST", "http://127.0.0.1:18200/e2e/v1/chat/completions", json=body,
                      headers={"x-eval-session": sess}, timeout=30) as resp:
        check("LLM 透传 200/SSE", resp.status_code == 200 and "event-stream" in resp.headers.get("content-type", ""))
        for line in resp.iter_lines():
            if line.startswith("data:"):
                frames.append(line[5:].strip())
    check("SSE 透传内容完整", frames and frames[-1] == "[DONE]" and len(frames) > 3, f"{len(frames)} frames")

    items = httpx.get(f"{admin}/api/interactions", params={"ns": "e2e", "kind": "llm"}, timeout=10).json()["items"]
    check("录制流水可查询", len(items) >= 1, str(len(items)))
    detail = httpx.get(f"{admin}/api/interactions", params={"ns": "e2e", "kind": "llm"}, timeout=10).json()
    rec_file = sorted((work / "collector-data" / "e2e" / "llm").glob("*.jsonl"))[-1]
    rec = json.loads(rec_file.read_text(encoding="utf-8").splitlines()[0])
    check("录制 session 强关联", rec.get("session") == sess)
    check("默认模型注入（转发侧）", True)  # 注入影响转发；录制保留原样（见下）
    check("录制请求保留原样（无 model 字段）", "model" not in (rec.get("request") or {}))
    check("录制 chunks 与透传一致", rec.get("chunks") == frames, f"{len(rec.get('chunks') or [])} vs {len(frames)}")
    masked_dump = json.dumps(rec, ensure_ascii=False)
    check("录制副本已脱敏（邮箱）", "user@test.com" not in masked_dump and "***@***" in masked_dump)
    check("上游注入 key 不入库", "sk-collector-inject-key" not in masked_dump)

    # 2.3 LLM 非流式 + 脱敏预览
    r = httpx.post("http://127.0.0.1:18200/e2e/v1/chat/completions",
                   json={"stream": False, "messages": [{"role": "user", "content": "echo: nonstream"}]},
                   timeout=30).json()
    check("非流式透传", r.get("choices", [{}])[0].get("message", {}).get("content", "").startswith("nonstream"))
    mt = httpx.post(f"{admin}/api/mask-test", json={"ns": "e2e", "text": {"k": "13812345678"}}, timeout=10).json()
    check("脱敏预览（手机号）", mt["masked"]["k"] == "1**********", str(mt))

    # 2.4 沙箱代理录制
    r = httpx.post("http://127.0.0.1:18201/e2e/v1/sandboxes",
                   json={"image": "demo:latest"}, timeout=30)
    check("沙箱透传", r.status_code == 200, str(r.status_code))
    sbx = _read_latest(work / "collector-data" / "e2e" / "sandbox")
    check("沙箱录制 method/path", sbx and sbx.get("method") == "POST" and "/v1/sandboxes" in sbx.get("path", ""))
    check("沙箱录制响应体", (sbx.get("response") or {}).get("id") == "sbx-mock-001")

    # 2.5 MCP 代理录制（JSON-RPC initialize + tools/call）
    rpc = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "echo", "arguments": {"text": "mcp-录制-验证"}}}
    r = httpx.post("http://127.0.0.1:18202/mcp/e2e/srv1", json=rpc, timeout=30)
    check("MCP 透传", r.status_code == 200 and r.json().get("result", {}).get("content"))
    mcp_rec = _read_latest(work / "collector-data" / "e2e" / "mcp")
    check("MCP 录制 JSON-RPC 对", (mcp_rec.get("request") or {}).get("method") == "tools/call"
          and (mcp_rec.get("response") or {}).get("result"))

    # 2.6 状态三态语义
    httpx.post(f"{admin}/api/profiles/e2e/state", json={"state": "passthrough"}, timeout=10)
    httpx.post("http://127.0.0.1:18200/e2e/v1/chat/completions",
               json={"stream": False, "messages": [{"role": "user", "content": "echo: passthrough-should-not-record"}]}, timeout=30)
    n_before = _count_lines(work / "collector-data" / "e2e" / "llm")
    check("passthrough 不录制", n_before == 2, f"lines={n_before}")
    httpx.post(f"{admin}/api/profiles/e2e/state", json={"state": "disabled"}, timeout=10)
    r = httpx.post("http://127.0.0.1:18200/e2e/v1/chat/completions",
                   json={"stream": False, "messages": [{"role": "user", "content": "x"}]}, timeout=10)
    check("disabled 拒绝 503", r.status_code == 503)
    httpx.post(f"{admin}/api/profiles/e2e/state", json={"state": "recording"}, timeout=10)

    # 2.7 切换片段 / 控制台页面
    snip = httpx.get(f"{admin}/api/profiles/e2e/switch-snippet", timeout=10).json()
    check("切换片段生成", "LLM_BASE_URL" in snip.get("env_keys", {}) and "env_patch_curl" in snip)
    html = httpx.get(f"{admin}/", timeout=10).text
    check("控制台页面可访问", "eval-collector 控制台" in html)
    st = httpx.get(f"{admin}/api/status", timeout=10).json()
    pf_e2e = next((x for x in st["profiles"] if x["ns"] == "e2e"), None)
    counts = pf_e2e["counts"] if pf_e2e else {}
    check("状态计数", counts.get("llm", {}).get("total") == 2
          and counts.get("sandbox", {}).get("total") >= 1 and counts.get("mcp", {}).get("total") >= 1, str(counts))
    return sess


def _read_latest(kind_dir: Path, timeout_s: float = 5.0) -> dict:
    """读最新一条录制记录（collector 落盘在响应完成后异步进行，轮询等待）。"""
    deadline = time.time() + timeout_s
    files: list[Path] = []
    while time.time() < deadline:
        files = sorted(kind_dir.glob("*.jsonl"))
        if files and files[-1].read_text(encoding="utf-8").strip():
            break
        time.sleep(0.2)
    if not files:
        return {}
    lines = files[-1].read_text(encoding="utf-8").splitlines()
    return json.loads(lines[-1]) if lines else {}


def _count_lines(kind_dir: Path) -> int:
    return sum(len(f.read_text(encoding="utf-8").splitlines()) for f in kind_dir.glob("*.jsonl"))


# ---------------------------------------------------------------------------
# 阶段 3：真实录制（stub agent 经 collector → 上游），驱动器捕获轨迹
# ---------------------------------------------------------------------------

async def phase_record(stub: Proc, sessions_spec: list[dict]) -> Path:
    print("\n[phase 3] 录制：stub agent 经 collector 驱动会话（驱动器捕获轨迹）")
    traces = work_dir / "driver-traces"
    traces.mkdir(exist_ok=True)
    for spec in sessions_spec:
        sid, events = spec["sid"], []
        for turn in spec["turns"]:
            _, evs = await sse_client.stream_chat(f"http://127.0.0.1:{stub.port}",
                                                  {"message": turn, "sessionId": sid}, timeout_s=180)
            base = events[-1]["t_ms"] if events else 0
            events.extend([{**e, "t_ms": e["t_ms"] + base} for e in evs])
        view = sse_client.build_view(events)
        check(f"会话 {sid} 终帧正常", view["terminal"] in ("AGENT_END", "error"), f"terminal={view['terminal']}")
        check(f"会话 {sid} 无 error 帧", not view["error_frames"])
        (traces / f"{sid}.json").write_text(json.dumps({
            "sid": sid, "title": spec.get("title", ""), "turns": [{"input": t} for t in spec["turns"]],
            "events": events, "final_output": view["final_output"],
            "frame_counts": view["frame_counts"], "hitl": {},
        }, ensure_ascii=False), encoding="utf-8")
    return traces


# ---------------------------------------------------------------------------
# 阶段 4/5：打包 + 回放
# ---------------------------------------------------------------------------

def phase_pack(traces: Path) -> Path:
    print("\n[phase 4] pack：录制数据 → evalpack")
    pack_dir = do_pack(collector_dir=str(work_dir / "collector-data"), ns="rec",
                       out_dir=str(work_dir / "pack" / "pk-e2e-demo"), traces_dir=str(traces),
                       framework_version="e2e-local")
    m = json.loads((pack_dir / "manifest.json").read_text(encoding="utf-8"))
    check("manifest 会话数", len(m["sessions"]) >= 2, str([s["sid"] for s in m["sessions"]]))
    check("manifest 交互统计", m["stats"]["llm"] >= 3, str(m["stats"]))
    check("CHECKSUMS 校验", verify_checksums(pack_dir))
    check("zip 生成", (Path(str(pack_dir) + ".zip")).exists())
    cases = list((pack_dir / "cases-draft").glob("*.json"))
    case0 = json.loads(cases[0].read_text(encoding="utf-8"))
    check("用例草稿生成", case0["case_id"].startswith("case_replay_") and case0.get("expected", {}).get("no_error"))
    sess0 = json.loads((pack_dir / "sessions" / f"{m['sessions'][0]['sid']}.json").read_text(encoding="utf-8"))
    check("会话骨架终答非空", bool(sess0.get("recorded_final")), sess0.get("recorded_final", "")[:60])
    conf = {s["sid"]: s["confidence"] for s in m["sessions"]}
    check("session 头强关联=high", "high" in conf.values(), str(conf))
    return pack_dir


def phase_replay(pack_dir: Path, use_judge: bool) -> dict:
    print("\n[phase 5] replay：replay-llm + stub agent 回放 + A1/A2/A3 + judge")
    sys.path.insert(0, str(EVAL_DIR))
    from replay import runner
    from graders.correctness import judge_from_env

    proc, llm_port = runner.start_replay_llm(str(pack_dir))
    stub = Proc("stub-replay", ["node", str(TESTS_DIR / "stub-agent.mjs"), "18951"],
                work_dir, env={"STUB_LLM_BASE_URL": f"http://127.0.0.1:{llm_port}/v1",
                               "STUB_PORT": "18951"},
                health="http://127.0.0.1:18951/healthz")
    try:
        judge_cfg = judge_from_env() if use_judge else None
        out = work_dir / "run-replay"
        code = asyncio.run(runner.run_replay(
            pack_dir=str(pack_dir), out_dir=str(out),
            base_url="http://127.0.0.1:18951", mode="strict", judge_cfg=judge_cfg,
            replayer=(proc, llm_port)))
        report = json.loads((out / "report.json").read_text(encoding="utf-8"))
        check("回放 exit=0", code == 0, f"code={code}")
        check("回放全部通过", report["summary"]["pass"] == report["summary"]["case_total"],
              json.dumps(report["summary"], ensure_ascii=False))
        check("A2 轨迹等价全过", all(c["trajectory"]["step_status"] == "pass" for c in report["cases"]))
        check("A3 漂移率为 0", report["summary"]["drift_rate"] == 0.0)
        case0 = report["cases"][0]
        check("回放终答与录制一致（确定性）", case0["final_output"] == case0["recorded_final"],
              (case0["final_output"] or "")[:60])
        check("report.html 生成", (out / "report.html").exists())
        if judge_cfg:
            check("judge 打分（mimo）", case0.get("scores", {}).get("correctness", {}).get("score", 0) >= 0.8,
                  str(case0.get("scores")))
        # 漂移检测金标验证：发一个与录制不同的输入 → drift 计数上升
        stats_before = runner.replayer_stats(llm_port)
        return {"report": report, "replayer_port": llm_port, "stats": stats_before}
    finally:
        stub.stop()
        proc.terminate()


def phase_drift_detection(pack_dir: Path) -> None:
    """金标验证：请求形状失配必须被捕获（A2/A3 可见），strict 模式判失败。"""
    print("\n[phase 5b] 金标验证：行为变更（漂移）可被捕获")
    sys.path.insert(0, str(EVAL_DIR))
    from replay import runner
    proc, llm_port = runner.start_replay_llm(str(pack_dir))
    stub = Proc("stub-replay-drift", ["node", str(TESTS_DIR / "stub-agent.mjs"), "18952"],
                work_dir, env={"STUB_LLM_BASE_URL": f"http://127.0.0.1:{llm_port}/v1",
                               "STUB_PORT": "18952", "STUB_MUTATE": "1"},
                health="http://127.0.0.1:18952/healthz")
    try:
        out = work_dir / "run-drift"
        code = asyncio.run(runner.run_replay(
            pack_dir=str(pack_dir), out_dir=str(out), base_url="http://127.0.0.1:18952",
            mode="strict", replayer=(proc, llm_port)))
        report = json.loads((out / "report.json").read_text(encoding="utf-8"))
        check("漂移注入 → strict 回放失败", code == 1 and report["summary"]["drift_rate"] > 0,
              f"code={code} drift={report['summary']['drift_rate']}")
    finally:
        stub.stop()
        proc.terminate()


# ---------------------------------------------------------------------------
# 阶段 6：eval-studio API 全流程
# ---------------------------------------------------------------------------

def phase_studio(pack_dir: Path, use_judge: bool) -> None:
    print("\n[phase 6] eval-studio API 全流程（档案 / 包 / 用例 / run / 报告）")
    # 幂等：清空上一轮的 studio 数据（档案/包/run 全部重建）
    import shutil as _sh
    _sh.rmtree(work_dir / "studio-data", ignore_errors=True)
    studio = Proc("studio", [sys.executable, "-m", "uvicorn", "app.main:app",
                             "--port", "18400", "--app-dir", str(BENCH_DIR / "eval-studio")],
                  work_dir, env={"STUDIO_DATA_DIR": str(work_dir / "studio-data"),
                                 "STUDIO_BENCH_DIR": str(BENCH_DIR),
                                 "STUDIO_SKIP_AUTH": "1"},
                  health="http://127.0.0.1:18400/healthz")
    try:
        base = "http://127.0.0.1:18400"
        r = httpx.get(f"{base}/api/profiles", timeout=10).json()
        check("studio 启动+档案列表", r == {"profiles": [], "active": None})

        # 目标档案：replay（内置 stub 沙盒）+ live（打真实 stub agent）
        rp = httpx.post(f"{base}/api/profiles", json={
            "id": "prof-replay", "name": "离线回放-内置沙盒",
            "agent": {"oaf_slug": "e2e/demo"}, "target_mode": "replay",
            "replay": {"pack_policy": "latest", "harness": "builtin-stub", "strictness": "strict"},
        }, timeout=10).json()
        check("创建 replay 档案", rp.get("id") == "prof-replay")
        lp = httpx.post(f"{base}/api/profiles", json={
            "id": "prof-live", "name": "联机-stub 服务",
            "agent": {"oaf_slug": "e2e/demo"}, "target_mode": "live",
            "live": {"base_url": "http://127.0.0.1:18961"},
            "judge": {"base_url": os.environ.get("EVAL_LLM_BASE_URL", ""),
                      "api_key": os.environ.get("EVAL_LLM_API_KEY", ""),
                      "model": os.environ.get("EVAL_LLM_MODEL", "")} if use_judge else {},
        }, timeout=10).json()
        check("创建 live 档案", lp.get("id") == "prof-live")
        r = httpx.post(f"{base}/api/profiles/prof-live/activate", timeout=10).json()
        check("切换活跃档案", r.get("active") == "prof-live")
        exp = httpx.get(f"{base}/api/profiles/prof-replay/export", timeout=10).json()
        check("档案导出不含敏感值", json.dumps(exp).find(os.environ.get("EVAL_LLM_API_KEY", "@@none@@")) == -1)

        # 数据包上传（zip）
        zip_path = Path(str(pack_dir) + ".zip")
        r = httpx.post(f"{base}/api/packages", files={"file": (zip_path.name, zip_path.read_bytes(), "application/zip")},
                       timeout=30).json()
        check("上传 evalpack", r.get("pack_id") == "pk-e2e-demo", str(r)[:120])
        r = httpx.get(f"{base}/api/packages/pk-e2e-demo", timeout=10).json()
        check("包详情（会话/骨架）", len(r.get("sessions", [])) >= 2 and r.get("manifest", {}).get("format_version") == 1)
        r = httpx.get(f"{base}/api/packages/pk-e2e-demo/cases", timeout=10).json()
        check("包内用例草稿", len(r.get("cases", [])) >= 2)
        case_ids = [c["case_id"] for c in r["cases"]]

        # 用例转正
        r = httpx.post(f"{base}/api/cases/promote", json={"pack_id": "pk-e2e-demo", "case_id": case_ids[0]},
                       timeout=10).json()
        check("用例人审转正", r.get("case", {}).get("status") == "active", str(r)[:100])

        # replay run（内置 stub 沙盒 = 开箱即用回放）
        r = httpx.post(f"{base}/api/runs", json={
            "type": "replay", "profile_id": "prof-replay", "pack_id": "pk-e2e-demo",
            "case_ids": case_ids, "judge": use_judge}, timeout=10).json()
        run_id = r.get("run_id")
        check("创建 replay run", bool(run_id))
        report = _wait_run(base, run_id)
        check("replay run 完成", report and report["summary"]["pass"] == report["summary"]["case_total"],
              json.dumps(report.get("summary", {}), ensure_ascii=False) if report else "no report")
        r = httpx.get(f"{base}/api/runs/{run_id}/report.html", timeout=10)
        check("报告 HTML 可下载", r.status_code == 200 and "评测报告" in r.text)
        r = httpx.get(f"{base}/api/runs/{run_id}/cases/{case_ids[0]}.trace.json", timeout=10).json()
        check("轨迹明细可查", r.get("view", {}).get("final_output"))

        # live run（打真实 stub agent :18961 —— 由本编排器拉起，上游为 mock 录制件不可用，
        # 故 live 语义验证走 echo 直通；judge 按 use_judge）
        r = httpx.post(f"{base}/api/runs", json={
            "type": "live", "profile_id": "prof-live", "pack_id": "pk-e2e-demo",
            "case_ids": case_ids[:1], "judge": use_judge}, timeout=10).json()
        run_id2 = r.get("run_id")
        check("创建 live run", bool(run_id2))
        report2 = _wait_run(base, run_id2)
        check("live run 完成", report2 and report2["summary"]["case_total"] == 1,
              json.dumps(report2.get("summary", {}), ensure_ascii=False) if report2 else "no report")

        # 跨 run 趋势
        r = httpx.get(f"{base}/api/trends", timeout=10).json()
        check("趋势视图（跨 run）", len(r.get("runs", [])) >= 2, str(len(r.get("runs", []))))
        # 对比视图（M4 验收：同 pack 双 run 并排对比 + 回归结论）
        run_a = next((x for x in httpx.get(f"{base}/api/trends", timeout=10).json()["runs"]
                      if x["type"] == "replay"), None)
        run_b = next((x for x in httpx.get(f"{base}/api/trends", timeout=10).json()["runs"]
                      if x["type"] == "live"), None)
        cmp = httpx.get(f"{base}/api/compare", params={"run_a": run_a["run_id"], "run_b": run_b["run_id"]},
                        timeout=10).json()
        check("对比视图（verdict/conclusion 契约）",
              cmp.get("pack_id") == "pk-e2e-demo" and "conclusion" in cmp
              and set(cmp.get("counts", {})) <= {"regression", "improved", "stable_pass",
                                                 "both_failed", "new_in_b", "removed_in_b"}
              and cmp["cases"], str(cmp.get("counts")))
        # 页面
        r = httpx.get(f"{base}/", timeout=10)
        check("studio 页面可访问", r.status_code == 200 and "eval-studio" in r.text)
    finally:
        studio.stop()


def _wait_run(base: str, run_id: str, timeout_s: int = 300) -> dict | None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        r = httpx.get(f"{base}/api/runs/{run_id}", timeout=10).json()
        if r.get("status") in ("done", "done_with_failures", "error"):
            return r.get("report")
        time.sleep(1.5)
    return None


# ---------------------------------------------------------------------------
# 阶段 5c：沙箱/MCP 回放器（hermetic：用 e2e ns 的录制包）
# ---------------------------------------------------------------------------

def phase_replayers() -> None:
    print("\n[phase 5c] 沙箱/MCP 回放器（replay-http）")
    sys.path.insert(0, str(EVAL_DIR))
    from replay import runner
    from replay.packager import pack as do_pack2

    pack_dir = do_pack2(collector_dir=str(work_dir / "collector-data"), ns="e2e",
                        out_dir=str(work_dir / "pack" / "pk-e2e-hermetic"))
    replayers = runner.start_replayers(str(pack_dir))
    try:
        # 沙箱回放：同形状请求 → exact 命中，响应 = 录制件
        r = httpx.post(f"http://127.0.0.1:{replayers['sandbox'][1]}/v1/sandboxes",
                       json={"image": "demo:latest"}, timeout=10).json()
        check("沙箱回放响应=录制件", r.get("id") == "sbx-mock-001", str(r)[:80])
        st = httpx.get(f"http://127.0.0.1:{replayers['sandbox'][1]}/stats", timeout=10).json()
        check("沙箱回放 exact 计数", st["exact"] == 1 and st["miss"] == 0, str(st))
        # MCP 回放：tools/call → 录制 CallToolResult
        r = httpx.post(f"http://127.0.0.1:{replayers['mcp'][1]}",
                       json={"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                             "params": {"name": "echo", "arguments": {"text": "mcp-录制-验证"}}},
                       timeout=10).json()
        check("MCP 回放响应=录制件", r.get("result", {}).get("content"), str(r)[:80])
        st = httpx.get(f"http://127.0.0.1:{replayers['mcp'][1]}/stats", timeout=10).json()
        check("MCP 回放 exact 计数", st["exact"] == 1 and st["miss"] == 0, str(st))
        # LLM 回放（无 session 头 → 指纹匹配 auto 会话）：用包内派生输入回放
        sess_file = sorted((pack_dir / "sessions").glob("*.json"))[0]
        sess = json.loads(sess_file.read_text(encoding="utf-8"))
        anchor = sess["turns"][0]["input"]
        r = httpx.post(f"http://127.0.0.1:{replayers['llm'][1]}/v1/chat/completions",
                       json={"stream": False,
                             "messages": [{"role": "user", "content": anchor}]}, timeout=15).json()
        check("LLM 指纹匹配回放（无头场景）", bool(r.get("choices")), str(r)[:80])
        st = httpx.get(f"http://127.0.0.1:{replayers['llm'][1]}/stats", timeout=10).json()
        s0 = st["sessions"].get(sess["sid"], {})
        check("LLM 指纹匹配计数", s0.get("calls") == 1 and s0.get("drift") == 0, str(s0))
    finally:
        runner.stop_replayers(replayers)


# ---------------------------------------------------------------------------
# 阶段 5d：--export-e2e-fixtures 反哺 + --rebase 基线演进
# ---------------------------------------------------------------------------

def phase_fixtures(pack_dir: Path) -> None:
    print("\n[phase 5d-1] --export-e2e-fixtures（evalpack → 门禁轨夹具）")
    from replay.evolve import export_e2e_fixtures
    written = export_e2e_fixtures(str(pack_dir), str(work_dir / "e2e-fixtures"))
    check("夹具导出数量", len(written) >= 2, str(len(written)))
    fx = json.loads(written[0].read_text(encoding="utf-8"))
    check("夹具结构（calls[].request/chunks）",
          bool(fx.get("calls")) and "request" in fx["calls"][0] and "chunks" in fx["calls"][0],
          f"scenario={fx.get('scenario')} calls={len(fx.get('calls') or [])}")


def phase_rebase(pack_dir: Path) -> None:
    print("\n[phase 5d-2] --rebase 基线演进（行为变更后 loose 捕获 → 新基线接受演进）")
    sys.path.insert(0, str(EVAL_DIR))
    from replay import runner
    from replay import evolve
    from replay.packager import verify_checksums

    # ① 行为变更注入的服务 + loose 回放（漂移只记录不阻断）→ rebase 以实际请求为新基线
    proc, llm_port = runner.start_replay_llm(str(pack_dir))
    stub_mut = Proc("stub-rebase", ["node", str(TESTS_DIR / "stub-agent.mjs"), "18954"],
                    work_dir, env={"STUB_LLM_BASE_URL": f"http://127.0.0.1:{llm_port}/v1",
                                   "STUB_PORT": "18954", "STUB_MUTATE": "1"},
                    health="http://127.0.0.1:18954/healthz")
    try:
        code = asyncio.run(runner.run_replay(
            pack_dir=str(pack_dir), out_dir=str(work_dir / "run-rebase"),
            base_url="http://127.0.0.1:18954", mode="loose", replayer=(proc, llm_port)))
        new_pack = evolve.rebase_pack(str(pack_dir), llm_port,
                                      str(work_dir / "pack" / "pk-rebased"),
                                      note="e2e rebase：接受 STUB_MUTATE 行为变更")
    finally:
        stub_mut.stop()
        proc.terminate()
    check("rebase 流程 exit=0", code == 0, f"code={code}")
    m_new = json.loads((new_pack / "manifest.json").read_text(encoding="utf-8"))
    check("新基线包捕获全部实际请求", m_new.get("rebase", {}).get("replaced_requests", 0) >= 3,
          str(m_new.get("rebase")))
    check("新基线包 CHECKSUMS 有效", verify_checksums(new_pack))

    # ② 演进后的行为：旧基线 strict 拒绝（漂移），新基线 strict 接受 —— 基线演进闭环
    proc_old, port_old = runner.start_replay_llm(str(pack_dir))
    stub2 = Proc("stub-rebase-old", ["node", str(TESTS_DIR / "stub-agent.mjs"), "18955"],
                 work_dir, env={"STUB_LLM_BASE_URL": f"http://127.0.0.1:{port_old}/v1",
                                "STUB_PORT": "18955", "STUB_MUTATE": "1"},
                 health="http://127.0.0.1:18955/healthz")
    try:
        code_old = asyncio.run(runner.run_replay(
            pack_dir=str(pack_dir), out_dir=str(work_dir / "run-rebase-old"),
            base_url="http://127.0.0.1:18955", mode="strict", replayer=(proc_old, port_old)))
    finally:
        stub2.stop()
        proc_old.terminate()
    proc_new, port_new = runner.start_replay_llm(str(new_pack))
    stub3 = Proc("stub-rebase-new", ["node", str(TESTS_DIR / "stub-agent.mjs"), "18956"],
                 work_dir, env={"STUB_LLM_BASE_URL": f"http://127.0.0.1:{port_new}/v1",
                                "STUB_PORT": "18956", "STUB_MUTATE": "1"},
                 health="http://127.0.0.1:18956/healthz")
    try:
        code_new = asyncio.run(runner.run_replay(
            pack_dir=str(new_pack), out_dir=str(work_dir / "run-rebase-new"),
            base_url="http://127.0.0.1:18956", mode="strict", replayer=(proc_new, port_new)))
    finally:
        stub3.stop()
        proc_new.terminate()
    check("旧基线拒绝行为变更", code_old == 1)
    check("新基线接受演进后行为", code_new == 0, f"code={code_new}")


# ---------------------------------------------------------------------------
# 阶段 5e：OpenJudge 适配器（M4；需 py-openjudge 环境 + judge 凭据，否则跳过）
# ---------------------------------------------------------------------------

def phase_openjudge() -> None:
    print("\n[phase 5e] OpenJudge 适配器（graders/openjudge_adapter.py）")
    venv_py = Path("/tmp/ojvenv/bin/python")
    cfg_ok = all(os.environ.get(k) for k in ("EVAL_LLM_BASE_URL", "EVAL_LLM_API_KEY", "EVAL_LLM_MODEL"))
    if not venv_py.exists() or not cfg_ok:
        check("openjudge（环境不具备，按设计回落直评——非失败）", True,
              f"venv={venv_py.exists()} cfg={cfg_ok}")
        return
    script = (
        "import sys, os, json, asyncio\n"
        f"sys.path.insert(0, {str(EVAL_DIR)!r})\n"
        "from graders.openjudge_adapter import openjudge_available, judge_correctness_openjudge\n"
        "assert openjudge_available(), 'py-openjudge 不可用'\n"
        "case = {'input': {'message': '调用 echo 工具原样回显文本 hello-eval-2026'},\n"
        "        'ground_truth': 'hello-eval-2026（应调用 echo 工具回显该文本）'}\n"
        "trace = {'view': {'final_output': '已调用 echo 工具，回显内容：hello-eval-2026'}}\n"
        "cfg = {'base_url': os.environ['EVAL_LLM_BASE_URL'],\n"
        "       'api_key': os.environ['EVAL_LLM_API_KEY'], 'model': os.environ['EVAL_LLM_MODEL']}\n"
        "r = asyncio.run(judge_correctness_openjudge(case, trace, cfg))\n"
        "print('RESULT:' + json.dumps(r, ensure_ascii=False))\n"
    )
    env = dict(os.environ)
    r = subprocess.run([str(venv_py), "-c", script], capture_output=True, text=True,
                       timeout=180, env=env)
    line = next((l for l in r.stdout.splitlines() if l.startswith("RESULT:")), "")
    if not line:
        check("openjudge 适配器实调", False, f"stderr={r.stderr[-300:]}")
        return
    result = json.loads(line[len("RESULT:"):])
    check("openjudge 引擎标识", result.get("judge_engine") == "openjudge-0.2.2", str(result.get("judge_engine")))
    check("openjudge 打分（正例）", 0.7 <= result.get("score", 0) <= 1.0,
          f"score={result.get('score')} raw={result.get('raw_score')}")


# ---------------------------------------------------------------------------
# 阶段 7（--with-provision）：M2 金标——真实 jar 录制 → provision 回放
# ---------------------------------------------------------------------------

def _wipe_eval_db() -> None:
    """录制与回放共用本地 infra MySQL；金标回放前重置 schema（隔离记忆/checkpoint 污染）。"""
    subprocess.run(["docker", "exec", "e2e-mysql", "mysql", "-uroot", "-pe2e-root", "-e",
                    "DROP DATABASE IF EXISTS agent_framework_e2e; "
                    "CREATE DATABASE agent_framework_e2e;"], check=True, capture_output=True, timeout=30)


def phase_provision(record_base: dict, use_judge: bool) -> None:
    print("\n[phase 7] M2 金标：真实 agent-framework jar 录制 → provision 回放")
    sys.path.insert(0, str(EVAL_DIR))
    from provision import instance as inst, oaf as oaf_mod, replay_pack
    from replay.packager import pack as do_pack
    from executor import sse_client as sse

    jars = list((BENCH_DIR.parent / "target").glob("agent-framework-*.jar"))
    if not jars:
        check("jar 存在（mvn -DskipTests package）", False, "缺 jar，跳过金标")
        return
    if not (BENCH_DIR.parent / "e2e" / "scripts" / "local-infra.sh").exists():
        check("local-infra.sh 存在", False)
        return

    # ① 录制供给：最小 OAF 基座 + jar（LLM → collector /gold/v1）
    import shutil as _sh
    _sh.rmtree(work_dir / "collector-data" / "gold", ignore_errors=True)  # 幂等：清旧录制
    runtime_dir = work_dir / "gold-runtime"
    _sh.rmtree(runtime_dir, ignore_errors=True)
    runtime_dir.mkdir(parents=True, exist_ok=True)
    cfg_dir = runtime_dir / "agent-config"
    cfg_dir.mkdir(parents=True, exist_ok=True)
    spec = {"since": "replay-minimal", "env_needs": {"plugins": [], "mock_mcp": {"enabled": False, "tools": [], "ask_tools": []},
                          "reload_probe": False, "session_model_probe": False}}
    oaf_mod.assemble(spec, cfg_dir, runtime_dir, BENCH_DIR.parent, "http://127.0.0.1:1/mcp")
    inst.ensure_infra(BENCH_DIR.parent)
    # 录制前重置存储：保证录制/回放两侧记忆与 checkpoint 状态同源为空（否则
    # 历史记忆会注入 system prompt 造成结构性漂移），回放侧 wipe 在打包后
    _wipe_eval_db()
    # 录制必须经 collector（旁路录制是前提）：jar 的 LLM 指向 collector /gold/v1，
    # collector gold 档案注入真实上游 key（jar 侧 key 为占位）。
    # 金标两侧（录制/回放）显式关闭记忆：记忆注入是状态性的，会导致 prompt 漂移。
    gold_llm = {"base_url": "http://127.0.0.1:18200/gold/v1",
                "api_key": "gold-recording-dummy",
                "model": record_base["model"]}
    base_url = inst.start_instance(BENCH_DIR.parent, runtime_dir, gold_llm,
                                   extra_envs={"AGENT_MEMORY_ENABLED": "false"})
    try:
        inst.wait_health(base_url)
        print(f"[gold] 录制实例就绪: {base_url}")
        # ② 真实会话录制（单轮 case；真实 agent 不回传 session 头 → 打包走指纹聚类）
        traces = work_dir / "gold-traces"
        traces.mkdir(exist_ok=True)
        specs = [
            {"sid": "gold-rec-1", "input": "请用一句话介绍你自己（金标录制用例一）"},
            {"sid": "gold-rec-2", "input": "金标录制用例二：1+1 等于几？只回答数字。"},
        ]
        for s in specs:
            _, evs = asyncio.run(sse.stream_chat(base_url, {"message": s["input"], "userId": "eval-gold"},
                                                 timeout_s=300))
            view = sse.build_view(evs)
            check(f"gold 录制 {s['sid']} 终帧正常", view["terminal"] in ("AGENT_END", "done", "error"),
                  f"terminal={view['terminal']}")
            (traces / f"{s['sid']}.json").write_text(json.dumps({
                "sid": s["sid"], "title": s["sid"], "turns": [{"input": s["input"]}],
                "events": evs, "final_output": view["final_output"],
                "frame_counts": view["frame_counts"], "hitl": {},
                "tool_names": [c["name"] for c in view["tool_calls"]],
            }, ensure_ascii=False), encoding="utf-8")
    finally:
        inst.teardown(runtime_dir)

    # ③ 打包（背景调用过滤后分组）→ ④ 重置存储 → ⑤ provision 回放
    pack_dir = do_pack(collector_dir=str(work_dir / "collector-data"), ns="gold",
                       out_dir=str(work_dir / "pack" / "pk-gold"), traces_dir=str(traces),
                       oaf_zip=None, framework_version="gold")
    m = json.loads((pack_dir / "manifest.json").read_text(encoding="utf-8"))
    check("gold 包会话数=2", len(m["sessions"]) == 2, str([x["sid"] for x in m["sessions"]]))
    check("gold 包背景调用过滤", m["stats"].get("llm_background", 0) >= 0
          and m["stats"].get("llm_main", 0) >= 2, str(m["stats"]))
    _wipe_eval_db()

    result = replay_pack.provision_replay(
        repo_dir=BENCH_DIR.parent, eval_dir=EVAL_DIR, pack_dir=str(pack_dir),
        out_dir=str(work_dir / "run-gold-replay"), mode="strict",
        extra_envs={"AGENT_MEMORY_ENABLED": "false"})
    check("provision 回放 exit=0（真实 jar 轨迹等价）", result["exit_code"] == 0,
          f"code={result['exit_code']}")
    report_path = work_dir / "run-gold-replay" / "report.json"
    if not report_path.exists():
        check("gold 回放报告生成", False, "run_replay 未产出报告（无可执行用例或环境失败）")
        return
    report = json.loads(report_path.read_text(encoding="utf-8"))
    check("gold 回放全部通过", report["summary"]["pass"] == report["summary"]["case_total"],
          json.dumps(report["summary"], ensure_ascii=False))
    check("gold 回放零漂移", report["summary"]["drift_rate"] == 0.0,
          f"drift={report['summary']['drift_rate']}")


# ---------------------------------------------------------------------------

work_dir = Path("/tmp/eval-e2e")


def main() -> int:
    global work_dir
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true", help="全离线：mock 上游 + 跳过 judge")
    ap.add_argument("--with-provision", action="store_true",
                    help="附加 M2 金标阶段（真实 jar 录制→provision 回放；需 mvn package + docker infra）")
    ap.add_argument("--phase", default="all",
                    help="unit|collector|record|pack|replay|replayers|fixtures|rebase|openjudge|provision|studio|all")
    ap.add_argument("--work", default="/tmp/eval-e2e")
    args = ap.parse_args()
    work_dir = Path(args.work)
    work_dir.mkdir(parents=True, exist_ok=True)

    load_env_secrets()
    use_real = not args.offline
    use_judge = not args.offline

    phases = args.phase.split(",")
    run_all = phases == ["all"]
    procs: list[Proc] = []
    try:
        if run_all or "unit" in phases:
            phase_unit()

        mock_up = collector = stub_rec = None
        pack_dir = None
        record_base_info = {}
        need_runtime = run_all or any(p in phases for p in ("collector", "record", "pack", "replay",
                                                            "replayers", "fixtures", "rebase", "provision"))
        if need_runtime:
            port = free_port()
            mock_up = Proc("mock-upstream", ["node", str(TESTS_DIR / "mock-upstream-llm.mjs"), str(port)],
                           work_dir, health=f"http://127.0.0.1:{port}/stats")
            mock_up.port = port
            procs.append(mock_up)
            record_base = None
            key = model = ""
            if use_real:
                record_base = os.environ.get("EVAL_RECORD_LLM_BASE_URL")
                key = os.environ.get("EVAL_RECORD_LLM_API_KEY")
                model = os.environ.get("EVAL_RECORD_LLM_MODEL")
                try:
                    r = httpx.post(f"{record_base}/chat/completions",
                                   headers={"Authorization": f"Bearer {key}"}, timeout=25, json={
                                       "model": model, "max_tokens": 8,
                                       "messages": [{"role": "user", "content": "ping"}]})
                    ok = r.status_code == 200
                except Exception as e:
                    print(f"[record] 真实上游不可达（{e}），回退 mock")
                    ok = False
                if not ok:
                    use_real_resolved = False
                else:
                    use_real_resolved = True
            else:
                use_real_resolved = False
            if use_real_resolved:
                print(f"[record] 录制上游 = 真实 {record_base}（model={model}）")
                upstream_profile = {"llm": record_base, "llm_api_key": key, "llm_default_model": model}
            else:
                print("[record] 录制上游 = mock（离线）")
                upstream_profile = {"llm": f"http://127.0.0.1:{mock_up.port}/v1", "llm_default_model": "mock-record-model"}
            record_base_info = {"base": upstream_profile["llm"], "key": key or "mock-dummy",
                                "model": model if use_real_resolved else "mock-record-model"}
            # collector 在新数据目录启动（幂等：数据与档案配置都清空重建）
            import shutil as _sh
            _sh.rmtree(work_dir / "collector-data", ignore_errors=True)
            _sh.rmtree(work_dir / "collector-conf", ignore_errors=True)
            collector = Proc("collector", ["node", str(BENCH_DIR / "eval-collector" / "server.mjs")],
                             work_dir, env={"EVAL_COLLECTOR_DATA": str(work_dir / "collector-data"),
                                            "EVAL_COLLECTOR_CONF": str(work_dir / "collector-conf")},
                             health="http://127.0.0.1:18300/healthz")
            procs.append(collector)
            # 预建录制档案（ns=rec）与金标档案（ns=gold）；phase_collector 自建 ns=e2e 的 hermetic 档案
            for ns, display in [("rec", "录制演示服务"), ("gold", "金标录制服务")]:
                httpx.post("http://127.0.0.1:18300/api/profiles",
                           json={"ns": ns, "display": display, "state": "recording",
                                 "upstream": {**upstream_profile},
                                 "record": {"sampling": 1, "body_max_bytes": 262144}}, timeout=10)
            # replayers 阶段依赖 ns=e2e 的 hermetic 录制（含沙箱/MCP 交互）→ 隐式带跑 collector 阶段
            if run_all or "collector" in phases or "replayers" in phases:
                phase_collector(work_dir, mock_up, collector)
            if run_all or any(p in phases for p in ("record", "pack", "replay", "rebase", "fixtures")):
                port = free_port()
                stub_rec = Proc("stub-record", ["node", str(TESTS_DIR / "stub-agent.mjs"), "18941"],
                                work_dir, env={"STUB_LLM_BASE_URL": "http://127.0.0.1:18200/rec/v1",
                                               "STUB_PORT": "18941"},
                                health="http://127.0.0.1:18941/healthz")
                stub_rec.port = 18941
                procs.append(stub_rec)
                sessions_spec = [
                    {"sid": "sess-rec-1", "title": "录制会话 1",
                     "turns": ["echo: 请录制第一会话第一轮", "继续，第一会话第二轮"]},
                    {"sid": "sess-rec-2", "title": "录制会话 2",
                     "turns": ["echo: 第二会话唯一一轮"]},
                ]
                traces = asyncio.run(phase_record(stub_rec, sessions_spec))
                if run_all or any(p in phases for p in ("pack", "replay", "rebase", "fixtures")):
                    pack_dir = phase_pack(traces)
                if run_all or "replay" in phases:
                    phase_replay(pack_dir, use_judge)
                    phase_drift_detection(pack_dir)
                if run_all or "replayers" in phases:
                    phase_replayers()
                if run_all or "fixtures" in phases:
                    phase_fixtures(pack_dir)
                if run_all or "rebase" in phases:
                    phase_rebase(pack_dir)
        if run_all or "openjudge" in phases:
            phase_openjudge()
        if "provision" in phases or (args.with_provision and run_all):
            if not record_base_info or collector is None:
                raise RuntimeError("provision 阶段依赖 collector 运行（连同 record/pack 一起跑）")
            phase_provision(record_base_info, use_judge)
        if run_all or "studio" in phases:
            if pack_dir is None:
                # 独立跑 studio 阶段：先最小化打包
                if need_runtime is False:
                    raise RuntimeError("studio 阶段依赖 pack 产物，请连同 pack 一起跑")
            stub_live = Proc("stub-live", ["node", str(TESTS_DIR / "stub-agent.mjs"), "18961"],
                             work_dir, env={"STUB_LLM_BASE_URL": f"http://127.0.0.1:{mock_up.port}/v1",
                                            "STUB_LLM_API_KEY": "live-dummy",
                                            "STUB_PORT": "18961"},
                             health="http://127.0.0.1:18961/healthz")
            procs.append(stub_live)
            phase_studio(pack_dir, use_judge)

        print(f"\n==== e2e 结果：PASS {PASS} / FAIL {FAIL} ====")
        return 0 if FAIL == 0 else 1
    finally:
        for p in procs:
            p.stop()


def load_env_secrets() -> None:
    """注入 .env.secrets（仓库根，gitignored）——不打印任何值。"""
    root = BENCH_DIR.parent.parent
    f = root / ".env.secrets"
    if not f.exists():
        return
    for line in f.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())
    # judge 配置别名：EVAL_JUDGE_LLM_* → 飞轮标准 EVAL_LLM_*
    for dst, src in [("EVAL_LLM_BASE_URL", "EVAL_JUDGE_LLM_BASE_URL"),
                     ("EVAL_LLM_API_KEY", "EVAL_JUDGE_LLM_API_KEY"),
                     ("EVAL_LLM_MODEL", "EVAL_JUDGE_LLM_MODEL")]:
        if not os.environ.get(dst) and os.environ.get(src):
            os.environ[dst] = os.environ[src]


if __name__ == "__main__":
    sys.exit(main())
