#!/usr/bin/env python3
"""agent-framework 评测飞轮编排（walking skeleton）。

对应设计 docs/design/agent-framework-eval-dual-track-design.md 的六步闭环：
  analyze（diff 变更分析）→ gen（LLM 用例生成，可选）→ eval（轨迹采集+确定性断言）
  → judge（语义打分，可选）→ rca（规则初筛+报告）→ verify（修复后回归）。

用法：
  python3 bench/eval/flywheel.py run [--base-url URL] [--since SHA] [--only ID]
                                     [--repeat N] [--workers N] [--with-gen N]
                                     [--judge] [--rca-llm] [--no-cleanup]
                                     [--provision] [--no-teardown]
  python3 bench/eval/flywheel.py provision --since SHA [--oaf-base DIR] [--plugin-src DIR]
  python3 bench/eval/flywheel.py teardown
  python3 bench/eval/flywheel.py verify [--task-id ID] [--base-url URL]
  python3 bench/eval/flywheel.py selftest
  python3 bench/eval/flywheel.py status

环境变量：
  EVAL_AGENT_BASE_URL            被测服务地址（默认 http://127.0.0.1:8100）
  EVAL_LLM_BASE_URL/_API_KEY/_MODEL  gen/judge/rca-llm 所需（与被测 Agent 的 LLM 无关）
"""

import argparse
import asyncio
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

EVAL_DIR = Path(__file__).resolve().parent
REPO_DIR = EVAL_DIR.parent.parent          # agent-framework/
CASES_DIR = EVAL_DIR / "cases"
REPORTS_DIR = EVAL_DIR / "reports"
HISTORY = REPORTS_DIR / "history.jsonl"

sys.path.insert(0, str(EVAL_DIR))
from executor import runner as suite_runner  # noqa: E402
from executor import checks as checks_mod  # noqa: E402
from executor import sse_client  # noqa: E402
from analyzer import rca as rca_mod  # noqa: E402


def _git_commit8() -> str:
    out = subprocess.run(["git", "rev-parse", "--short=8", "HEAD"],
                         cwd=REPO_DIR, capture_output=True, text=True, timeout=15)
    return out.stdout.strip() if out.returncode == 0 else "unknown"


def _default_base_url() -> str:
    import os
    return os.environ.get("EVAL_AGENT_BASE_URL", "http://127.0.0.1:8100")


def _append_history(line: dict[str, Any]) -> None:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    with open(HISTORY, "a", encoding="utf-8") as f:
        f.write(json.dumps(line, ensure_ascii=False) + "\n")


def _case_passed(traces: list[dict[str, Any]]) -> bool:
    """用例级通过 = 每次重复都 success 且确定性检查全过。"""
    return all(t["status"] == "success" and t["checks_passed"] for t in traces)


def _write_trace(task_dir: Path, trace: dict[str, Any]) -> str:
    rel = f"traces/{trace['case_id']}#{trace['repeat_no']}.json"
    with open(task_dir / rel, "w", encoding="utf-8") as f:
        json.dump(trace, f, ensure_ascii=False, indent=1)
    return rel


def _summary_row(task_id: str, trace: dict[str, Any], trace_path: str) -> dict[str, Any]:
    view = trace["view"]
    return {
        "task_id": task_id, "case_id": trace["case_id"], "session_id": trace["session_id"],
        "repeat_no": trace["repeat_no"], "status": trace["status"],
        "duration_ms": trace["duration_ms"],
        "event_count": len(trace["events"]),
        "tool_call_count": len(view["tool_calls"]),
        "input_tokens": view["token_usage"]["input"],
        "output_tokens": view["token_usage"]["output"],
        "has_file_ready": view["frame_counts"].get("file_ready", 0) > 0,
        "checks_passed": trace["checks_passed"],
        "trace_path": trace_path,
    }


async def cmd_run(args: argparse.Namespace) -> int:
    base_url = args.base_url or _default_base_url()
    env_tags: set[str] | None = None
    if args.provision:
        # ①.5 环境供给（设计 §4.5）：按变更组装 OAF 包/mock/实例，跑契约预检后进入原流程
        from provision import provision_env
        try:
            if not args.since:
                print("[provision] --provision 需要 --since <sha> 指定变更基线")
                return 2
            st = provision_env(EVAL_DIR, REPO_DIR, args.since,
                               base_dir=args.oaf_base, plugin_src=args.plugin_src)
            base_url = st["base_url"]
            env_tags = set(st["env_tags"])
        except Exception as e:
            print(f"[provision][FAIL] {e}")
            return 2
    else:
        from provision import load_runtime_state
        st = load_runtime_state(EVAL_DIR)
        if st and st.get("base_url") == base_url:
            env_tags = set(st.get("env_tags", []))
    commit = _git_commit8()
    task_id = f"{commit}-trend-{time.strftime('%Y%m%d-%H%M%S')}"
    task_dir = REPORTS_DIR / task_id
    (task_dir / "traces").mkdir(parents=True, exist_ok=True)
    (task_dir / "gen_cases").mkdir(parents=True, exist_ok=True)
    print(f"[flywheel] task={task_id} base={base_url}")

    # ---- ① 变更分析（纯规则） ----
    analysis: dict[str, Any] | None = None
    since = args.since
    if since is None and HISTORY.exists() and HISTORY.stat().st_size > 0:
        with open(HISTORY, encoding="utf-8") as f:
            lines = [l for l in f.read().splitlines() if l.strip()]
        if lines:
            since = json.loads(lines[-1]).get("commit")
    if since and since != commit:
        from casegen.generator import analyze_diff
        try:
            analysis = analyze_diff(REPO_DIR, since)
            with open(task_dir / "analyze.json", "w", encoding="utf-8") as f:
                json.dump(analysis, f, ensure_ascii=False, indent=1)
            mods = ", ".join(f"{m}({len(fs)})" for m, fs in analysis["modules"].items()) or "无映射模块"
            print(f"[analyze] since={since} 风险={analysis['risk']} 变更模块: {mods}")
        except Exception as e:
            print(f"[analyze] 跳过：{e}")
    else:
        print("[analyze] 跳过（无上一轮基线 commit，可用 --since 指定）")

    # ---- ② 用例生成（可选） ----
    gen_cases: list[dict[str, Any]] = []
    if args.with_gen > 0:
        from graders.correctness import judge_from_env
        from casegen.generator import generate_candidates
        cfg = judge_from_env()
        if cfg is None or analysis is None:
            print("[gen] 跳过（需要 EVAL_LLM_* 配置与变更分析结果）")
        else:
            capabilities = await suite_runner.fetch_capabilities(base_url)
            library = suite_runner.load_cases(CASES_DIR)
            candidates = await generate_candidates(
                analysis, capabilities, [c["input"]["message"] for c in library],
                cfg, args.with_gen, library)
            for c in candidates:
                with open(task_dir / "gen_cases" / f"{c['case_id']}.json", "w",
                          encoding="utf-8") as f:
                    json.dump(c, f, ensure_ascii=False, indent=1)
            # 闸门③动态冒烟：跑不通/超时的候选淘汰
            for c in candidates:
                t = await suite_runner.execute_once(c, base_url, repeat_no=1)
                if t["status"] == "success":
                    gen_cases.append(c)
                else:
                    print(f"[gen] 冒烟淘汰 {c['case_id']}: {t['status']} {t['error_info'] or ''}")
            print(f"[gen] 候选 {len(candidates)} 条，冒烟存活 {len(gen_cases)} 条")

    # ---- ③ 评测执行 + 确定性断言 ----
    library = suite_runner.load_cases(CASES_DIR)
    if args.only:
        pat = args.only.lower()
        library = [c for c in library if pat in c["case_id"].lower()]
    cases = library + gen_cases
    if not cases:
        print("[eval] 无可执行用例")
        return 2
    cases_map = {c["case_id"]: c for c in cases}
    capabilities = await suite_runner.fetch_capabilities(base_url)
    print(f"[eval] 用例 {len(cases)} 条（repeat={args.repeat}, workers={args.workers}），"
          f"被测能力 {len(capabilities)} 个工具")

    def on_trace(t: dict[str, Any]) -> None:
        # 增量进度：每条轨迹完成即打印并落盘（后续阶段崩溃/中断时轨迹数据不丢）
        mark = "?"  # 用例级 PASS/FAIL 需聚合全部重复，行级只报执行状态
        if t["status"] != "success":
            mark = "EXEC-FAIL"
        elif t["checks_passed"] is False:
            mark = "CHECK-FAIL"
        print(f"  [{time.strftime('%H:%M:%S')}] {t['case_id']}#{t['repeat_no']} "
              f"{t['status']} {t['duration_ms']}ms {mark if mark != '?' else ''}"
              f" {t['error_info'] or ''}".rstrip())
        try:
            _write_trace(task_dir, t)
        except OSError:
            pass

    traces: list[dict[str, Any]] = []
    try:
        traces, skipped = await suite_runner.run_suite(
            cases, base_url, repeat=args.repeat, workers=args.workers,
            capabilities=capabilities, env_tags=env_tags, on_trace=on_trace)

        by_case: dict[str, list[dict[str, Any]]] = {}
        for t in traces:
            by_case.setdefault(t["case_id"], []).append(t)
        for cid, ts in sorted(by_case.items()):
            mark = "PASS" if _case_passed(ts) else "FAIL"
            detail = ts[0]["error_info"] or ""
            print(f"  [{mark}] {cid} ({len(ts)} 次, {ts[0]['duration_ms']}ms) {detail}")

        # ---- ④ 语义打分（可选，advisory） ----
        judge_results: dict[str, dict[str, Any]] = {}
        if args.judge:
            from graders.correctness import judge_from_env, judge_correctness, JudgeUnavailable
            cfg = judge_from_env()
            if cfg is None:
                print("[judge] 跳过（未配置 EVAL_LLM_*）")
            else:
                for cid, ts in sorted(by_case.items()):
                    try:
                        judge_results[cid] = await judge_correctness(cases_map[cid], ts[0], cfg)
                        print(f"  [judge] {cid} = {judge_results[cid]['score']:.2f}")
                    except JudgeUnavailable as e:
                        print(f"  [judge] {cid} 失败: {e}")

        # ---- ⑤ 根因分析（规则初筛）+ 报告 ----
        failed = [cid for cid, ts in by_case.items() if not _case_passed(ts)]
        analyses: list[dict[str, Any]] = []
        for cid in failed:
            first_bad = next(t for t in by_case[cid] if t["status"] != "success" or not t["checks_passed"])
            a = rca_mod.classify_failure(first_bad)
            a["case_id"] = cid
            if args.rca_llm:
                from graders.correctness import judge_from_env
                from analyzer.rca import llm_rca
                cfg = judge_from_env()
                if cfg is not None:
                    try:
                        a["llm_root_cause"] = await llm_rca(cases_map[cid], first_bad, cfg)
                    except Exception as e:  # RCA 单项失败只降级，不阻断报告（曾有 ImportError 教训）
                        a["llm_root_cause"] = f"（LLM RCA 失败: {e}）"
            analyses.append(a)

        meta = {"commit": commit, "track": "trend", "trigger": "manual",
                "base_url": base_url, "repeat": args.repeat,
                "since": since, "case_count": len(by_case)}
        report = rca_mod.build_report(task_id, meta, traces, skipped, analyses, judge_results)
        (task_dir / "report.md").write_text(report, encoding="utf-8")

        # 轨迹已在 on_trace 完成时落盘（崩溃不丢数据），这里只写摘要索引
        with open(task_dir / "summary.jsonl", "w", encoding="utf-8") as f:
            for t in traces:
                rel = f"traces/{t['case_id']}#{t['repeat_no']}.json"
                f.write(json.dumps(_summary_row(task_id, t, rel), ensure_ascii=False) + "\n")
        judge_model = __import__("os").environ.get("EVAL_LLM_MODEL") if args.judge else None
        pass_count = len(by_case) - len(failed)
        pass_rate = pass_count / len(by_case) if by_case else 0.0
        with open(task_dir / "task.json", "w", encoding="utf-8") as f:
            json.dump({**meta, "pass_count": pass_count, "pass_rate": pass_rate,
                       "total_score": (sum(r["score"] for r in judge_results.values())
                                       / len(judge_results)) if judge_results else None,
                       "judge_model": judge_model,
                       "gen_case_count": len(gen_cases),
                       "analyses": analyses}, f, ensure_ascii=False, indent=1)
        _append_history({"task_id": task_id, "commit": commit, "track": "trend",
                         "case_count": len(by_case), "pass_rate": pass_rate,
                         "total_score": (sum(r["score"] for r in judge_results.values())
                                         / len(judge_results)) if judge_results else None,
                         "judge_model": judge_model,
                         "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
    finally:
        # ---- 会话清理：评测完成/后续阶段崩溃/中断都执行（共享实例不留垃圾） ----
        if not args.no_cleanup:
            try:
                deleted, still = await suite_runner.cleanup_sessions(base_url, traces)
                print(f"[cleanup] 已删除 {deleted} 个评测会话")
                if still:
                    print(f"[cleanup][WARN] {len(still)} 个会话删除失败（可能是 turn 租约未释放），"
                          f"请稍后手动处理: {sorted(still)}")
            except Exception as e:
                print(f"[cleanup][WARN] 清理异常: {e}")
        if args.provision and not args.no_teardown:
            from provision import instance as prov_inst
            prov_inst.teardown(EVAL_DIR / prov_inst.RUNTIME_NAME)
            print("[teardown] 已停止被测实例与 mock（infra 容器保留）")

    print(f"[done] 通过 {pass_count}/{len(by_case)}；报告: reports/{task_id}/report.md")
    return 0 if not failed else 1


def cmd_provision(args: argparse.Namespace) -> int:
    """①.5 环境供给（设计 §4.5）：按变更组装 OAF 包 + mock + 实例，预检后保留环境供调试/复用。"""
    from provision import provision_env
    try:
        st = provision_env(EVAL_DIR, REPO_DIR, args.since,
                           base_dir=args.oaf_base, plugin_src=args.plugin_src)
    except Exception as e:
        print(f"[provision][FAIL] {e}")
        return 2
    print(f"[provision] 环境保持运行：base_url={st['base_url']}（teardown 用 flywheel.py teardown）")
    return 0


def cmd_teardown(_: argparse.Namespace) -> int:
    from provision import instance as prov_inst
    prov_inst.teardown(EVAL_DIR / prov_inst.RUNTIME_NAME)
    print("[teardown] 已停止被测实例与 mock（infra 容器保留）")
    return 0


async def cmd_verify(args: argparse.Namespace) -> int:
    """⑥ 修复后回归：重跑上一轮失败用例 + core 回归集，输出转绿对比。"""
    base_url = args.base_url or _default_base_url()
    prev_task: dict[str, Any] | None = None
    if HISTORY.exists() and HISTORY.stat().st_size > 0:
        with open(HISTORY, encoding="utf-8") as f:
            lines = [l for l in f.read().splitlines() if l.strip()]
        wanted = args.task_id
        for line in reversed(lines):
            rec = json.loads(line)
            if wanted is None or rec["task_id"] == wanted:
                prev_task = rec
                break
    if prev_task is None:
        print("[verify] 找不到上一轮任务（先 run，或用 --task-id 指定）")
        return 2

    prev_dir = REPORTS_DIR / prev_task["task_id"]
    failed_ids: set[str] = set()
    if (prev_dir / "task.json").exists():
        with open(prev_dir / "task.json", encoding="utf-8") as f:
            failed_ids = {a["case_id"] for a in json.load(f).get("analyses", [])}
    library = suite_runner.load_cases(CASES_DIR)
    targets = [c for c in library
               if c["case_id"] in failed_ids or c.get("category") == "core"]
    if not targets:
        print("[verify] 无需回归的用例（上一轮无失败，无 core 用例）")
        return 0
    print(f"[verify] 回归 {len(targets)} 条（上一轮失败 {len(failed_ids & {c['case_id'] for c in targets})} 条 + core）")
    traces, _ = await suite_runner.run_suite(targets, base_url, repeat=args.repeat, workers=args.workers)
    by_case: dict[str, list[dict[str, Any]]] = {}
    for t in traces:
        by_case.setdefault(t["case_id"], []).append(t)

    commit = _git_commit8()
    task_id = f"{commit}-verify-{time.strftime('%Y%m%d-%H%M%S')}"
    task_dir = REPORTS_DIR / task_id
    (task_dir / "traces").mkdir(parents=True, exist_ok=True)
    transitions = []
    for cid, ts in sorted(by_case.items()):
        now = _case_passed(ts)
        was_fail = cid in failed_ids
        transition = ("转绿" if now and was_fail else
                      "仍失败" if was_fail else "通过" if now else "新失败")
        transitions.append({"case_id": cid, "was_failed": was_fail, "now_passed": now,
                            "transition": transition})
        print(f"  [{transition}] {cid}")
        for t in ts:
            _write_trace(task_dir, t)

    with open(task_dir / "task.json", "w", encoding="utf-8") as f:
        json.dump({"commit": commit, "track": "verify", "verify_of": prev_task["task_id"],
                   "case_count": len(by_case), "transitions": transitions},
                  f, ensure_ascii=False, indent=1)
    _append_history({"task_id": task_id, "commit": commit, "track": "verify",
                     "case_count": len(by_case),
                     "pass_rate": sum(1 for t in transitions if t["now_passed"]) / len(transitions),
                     "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
    if not args.no_cleanup:
        deleted, still = await suite_runner.cleanup_sessions(base_url, traces)
        print(f"[cleanup] 已删除 {deleted} 个评测会话")
        if still:
            print(f"[cleanup][WARN] {len(still)} 个会话删除失败: {sorted(still)}")
    ok = all(t["now_passed"] for t in transitions if t["was_failed"])
    print(f"[done] 上一轮失败用例{'全部转绿' if ok else '存在未转绿项'}；结果: reports/{task_id}/task.json")
    return 0 if ok else 1


def cmd_status(_: argparse.Namespace) -> int:
    if not HISTORY.exists():
        print("（暂无运行历史）")
        return 0
    with open(HISTORY, encoding="utf-8") as f:
        for line in f.read().splitlines():
            if line.strip():
                r = json.loads(line)
                score = f" score={r['total_score']:.2f}" if r.get("total_score") is not None else ""
                print(f"{r['ts']}  {r['task_id']}  通过率 {r['pass_rate']:.0%}{score}")
    return 0


def cmd_selftest(_: argparse.Namespace) -> int:
    """离线自检：帧映射解析 + 确定性检查器，不依赖网络与被测服务。"""
    mapping = sse_client.MAPPING
    assert "REQUIRE_USER_CONFIRM" in mapping["sdk_frames"]
    assert "permission_ask" in mapping["synthetic_frames"]

    events = [
        {"t_ms": 0, "type": "session_created", "raw": {"type": "session_created", "session_id": "s1"}},
        {"t_ms": 10, "type": "MODEL_CALL_END",
         "raw": {"type": "MODEL_CALL_END", "inputTokens": 100, "outputTokens": 20, "totalTokens": 120}},
        {"t_ms": 20, "type": "TOOL_CALL_START",
         "raw": {"type": "TOOL_CALL_START", "toolName": "echo", "toolCallId": "c1"}},
        {"t_ms": 25, "type": "tool_call_summary",
         "raw": {"type": "tool_call_summary", "tool_name": "echo"}},
        {"t_ms": 30, "type": "TEXT_BLOCK_DELTA", "raw": {"type": "TEXT_BLOCK_DELTA", "delta": "你好"}},
        {"t_ms": 40, "type": "TOOL_RESULT_END",
         "raw": {"type": "TOOL_RESULT_END", "toolCallId": "c1", "state": "SUCCESS"}},
        {"t_ms": 50, "type": "AGENT_END", "raw": {"type": "AGENT_END"}},
        {"t_ms": 60, "type": "done", "raw": {"type": "done"}},
    ]
    view = sse_client.build_view(events)
    assert view["final_output"] == "你好", view["final_output"]
    assert view["token_usage"] == {"input": 100, "output": 20, "total": 120}
    assert view["tool_calls"][0]["name"] == "echo"
    assert view["tool_calls"][0]["result_state"] == "SUCCESS"
    assert view["terminal"] == "done"
    assert view["frame_counts"]["session_created"] == 1

    ok = checks_mod.evaluate({
        "frames": {"done": 1, "error": 0, "file_ready": ">=0"},
        "frame_order": [["session_created", "done"]],
        "tool_calls": {"required": ["echo"], "forbidden": ["publish_service"]},
        "tool_result": {"echo": {"ok": True}},
        "final_text_min_len": 2,
        "no_error": True,
    }, view)
    assert all(c["passed"] for c in ok), ok

    bad = checks_mod.evaluate({
        "frames": {"done": 2},
        "frame_order": [["done", "session_created"]],
        "tool_calls": {"required": ["not_called"]},
        "tool_result": {"echo": {"ok": False}},
        "final_text_contains": ["不存在的内容xyz"],
        "final_text_min_len": 999,
    }, view)
    assert not any(c["passed"] for c in bad), bad

    hitl_events = events + [
        {"t_ms": 70, "type": "permission_ask",
         "raw": {"type": "permission_ask", "reply_id": "r1",
                 "tool_calls": [{"tool_call_id": "c9", "name": "publish_service", "input": {}}]}},
    ]
    hview = sse_client.build_view(hitl_events)
    assert hview["terminal"] == "permission_ask"
    assert hview["hitl"]["asks"][0]["tools"] == ["publish_service"]
    assert hview["hitl"]["asks"][0]["tool_calls"][0]["tool_call_id"] == "c9"

    err_view = sse_client.build_view(events + [
        {"t_ms": 70, "type": "error", "raw": {"type": "error", "error": "config load failed"}}])
    r = checks_mod.evaluate({"error_contains": ["config load"]}, err_view)
    assert r[0]["passed"]
    cat = rca_mod.classify_failure({"view": err_view, "error_info": None, "check_results": []})
    assert cat["category"] == "配置加载类", cat

    # /threads/chat 真实方言：无 done 帧，AGENT_END 收尾（2026-09-25 实测修正）
    no_done = sse_client.build_view([e for e in events if e["type"] != "done"])
    assert no_done["terminal"] == "AGENT_END", no_done["terminal"]
    r2 = checks_mod.evaluate({"frames": {"AGENT_END": 1, "done": 0}}, no_done)
    assert all(c["passed"] for c in r2), r2

    print(f"selftest PASS（帧映射 + 检查器 + HITL 视图 + 错误断言 + 失败分类，"
          f"{len(mapping['sdk_frames'])} 枚举 + {len(mapping['synthetic_frames'])} 合成帧）")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="agent-framework 评测飞轮")
    sub = p.add_subparsers(dest="cmd", required=True)

    pr = sub.add_parser("run", help="完整一圈：分析→生成(可选)→评测→打分(可选)→根因→报告")
    pr.add_argument("--base-url", default=None, help="被测服务地址")
    pr.add_argument("--since", default=None, help="变更分析基线 commit（默认取上一轮记录）")
    pr.add_argument("--only", default=None, help="只跑 case_id 含此子串的库内用例")
    pr.add_argument("--repeat", type=int, default=3, help="单用例重复次数（默认 3）")
    pr.add_argument("--workers", type=int, default=5, help="并发数（默认 5）")
    pr.add_argument("--with-gen", type=int, default=0, metavar="N",
                    help="启用 LLM 用例生成，每模块 N 条")
    pr.add_argument("--judge", action="store_true", help="启用语义打分（需 EVAL_LLM_*）")
    pr.add_argument("--rca-llm", action="store_true", help="失败项 LLM 深度根因（需 EVAL_LLM_*）")
    pr.add_argument("--no-cleanup", action="store_true", help="保留评测会话不删除")
    pr.add_argument("--provision", action="store_true",
                    help="先按变更供给评测环境（OAF 包/插件/mock MCP/实例，需 --since）")
    pr.add_argument("--no-teardown", action="store_true", help="供给模式下保留实例与 mock 不停止")
    pr.add_argument("--oaf-base", type=Path, default=None,
                    help="OAF 包 base 目录（缺省用 provision/templates/eval-agent）")
    pr.add_argument("--plugin-src", default=None, help="插件源码目录覆盖（缺省 e2e/plugin-echo）")
    pr.set_defaults(func=cmd_run)

    pp = sub.add_parser("provision", help="按变更供给评测环境（组装 OAF 包 + mock + 实例 + 预检）")
    pp.add_argument("--since", required=True, help="变更基线 commit")
    pp.add_argument("--oaf-base", type=Path, default=None, help="OAF 包 base 目录")
    pp.add_argument("--plugin-src", default=None, help="插件源码目录覆盖")
    pp.set_defaults(func=cmd_provision)

    pt = sub.add_parser("teardown", help="停止被测实例与 mock（infra 容器保留）")
    pt.set_defaults(func=cmd_teardown)

    pv = sub.add_parser("verify", help="修复后回归：重跑上一轮失败用例 + core 集")
    pv.add_argument("--task-id", default=None, help="指定上一轮任务（默认最近一轮）")
    pv.add_argument("--base-url", default=None)
    pv.add_argument("--repeat", type=int, default=1)
    pv.add_argument("--workers", type=int, default=5)
    pv.add_argument("--no-cleanup", action="store_true")
    pv.set_defaults(func=cmd_verify)

    ps = sub.add_parser("selftest", help="离线自检（无网络依赖）")
    ps.set_defaults(func=cmd_selftest)
    pst = sub.add_parser("status", help="查看运行历史")
    pst.set_defaults(func=cmd_status)

    args = p.parse_args()
    result = args.func(args)
    return asyncio.run(result) if asyncio.iscoroutine(result) else result


if __name__ == "__main__":
    sys.exit(main())
