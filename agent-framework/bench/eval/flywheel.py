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
  python3 bench/eval/flywheel.py export --target deepeval|langfuse|cases-legacy
                                       [--cases | --pack DIR | --run DIR]
                                       [--kind goldens|testcases] [--dataset NAME]
                                       [--out DIR] [--push] [--id-prefix PFX]
  python3 bench/eval/flywheel.py dataset (--list | --import-cases | --import-files GLOB
                                       | --import-skeleton DIR) [--service S] [--name N]
                                       [--status ST] [--limit N] [--item-id ID]
  python3 bench/eval/flywheel.py skeleton --db-name oaf_checkpoint --out DIR
                                       [--db-host H] [--db-port P] [--db-user U] [--since TS]

环境变量：
  EVAL_AGENT_BASE_URL            被测服务地址（默认 http://127.0.0.1:8100）
  EVAL_LLM_BASE_URL/_API_KEY/_MODEL  gen/judge/rca-llm 所需（与被测 Agent 的 LLM 无关）
  MYSQL_PWD                      skeleton 导出密码（只走环境变量，不落 argv/文件）
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


def _load_library() -> list[dict[str, Any]]:
    """执行链路唯一用例入口（收编统一）：DatasetHub active items → 用例字典。

    bench/eval/cases/ 自 PR2 起仅为兼容投影（export --target cases-legacy 再生），
    改用例请改 datasets/*/items/ 或走人审流程，勿手改投影。
    """
    from dataset import load_active_cases
    return load_active_cases()


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
        # 首帧/启动延迟单列（issue #87）：区分环境排队与用例本身慢
        "first_event_ms": view.get("first_event_ms"),
        "agent_start_ms": view.get("agent_start_ms"),
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
            library = _load_library()
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
            # 闸门收编：冒烟存活候选 → DatasetHub draft（人审/PR 转正后才进执行链路）
            if gen_cases:
                from dataset import DEFAULT_SERVICE, item_from_case, upsert_item
                for c in gen_cases:
                    try:
                        upsert_item(DEFAULT_SERVICE, "gen-drafts",
                                    item_from_case(c, source="g1_diff", status="draft"),
                                    changelog_note=f"gen {task_id}")
                    except Exception as e:  # 产物落库失败不阻断本轮评测（artifacts 已在 gen_cases/）
                        print(f"[gen][WARN] {c['case_id']} 入库失败: {e}")

    # ---- ③ 评测执行 + 确定性断言 ----
    library = _load_library()
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
                    except Exception as e:  # judge 单项失败只降级不阻断（issue #117：畸形 JSON 曾击穿整轮）
                        print(f"  [judge] {cid} 失败: {e}")
                        judge_results[cid] = {"score": None, "reason": f"judge unavailable: {e}"}

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
                "since": since, "case_count": len(by_case),
                # 帧序契约口径（issue #86 断代注记）：写入报告头/账本，跨轮对比先核对
                "contract": sse_client.MAPPING.get("version")}
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
        # judge 降级条目 score=None（issue #117）：均值只计有分项，全降级则为 None
        judge_scores = [r["score"] for r in judge_results.values() if r.get("score") is not None]
        with open(task_dir / "task.json", "w", encoding="utf-8") as f:
            json.dump({**meta, "pass_count": pass_count, "pass_rate": pass_rate,
                       "total_score": (sum(judge_scores) / len(judge_scores))
                                      if judge_scores else None,
                       "judge_model": judge_model,
                       # judge_results 单列（M3 scores 回流的机器可读来源）
                       "judge_results": judge_results,
                       "gen_case_count": len(gen_cases),
                       "analyses": analyses}, f, ensure_ascii=False, indent=1)
        _append_history({"task_id": task_id, "commit": commit, "track": "trend",
                         "case_count": len(by_case), "pass_rate": pass_rate,
                         "total_score": (sum(judge_scores) / len(judge_scores))
                                        if judge_scores else None,
                         "judge_model": judge_model,
                         "contract": sse_client.MAPPING.get("version"),
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
    library = _load_library()
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
                # 契约口径列（issue #86 断代）：旧行无此字段显示 "-"，跨轮对比注意口径差异
                contract = f" 契约={r['contract']}" if r.get("contract") else " 契约=-"
                print(f"{r['ts']}  {r['task_id']}  通过率 {r['pass_rate']:.0%}{score}{contract}")
    return 0


# ---------------------------------------------------------------------------
# 离线评测链路（设计 docs/design/agent-framework-eval-offline-record-replay-design.md）：
# pack（录制数据 → evalpack）/ replay（离线回放 + 轨迹等价）/ live（联机链路评测）
# ---------------------------------------------------------------------------

def cmd_pack(args: argparse.Namespace) -> int:
    from replay import packager
    out = packager.pack(
        collector_dir=args.collector_dir, out_dir=args.out, ns=args.ns,
        traces_dir=args.traces, oaf_zip=args.oaf_zip,
        since=args.since, until=args.until, framework_version=_git_commit8())
    with open(Path(out) / "manifest.json", encoding="utf-8") as f:
        manifest = json.load(f)
    print(f"[pack] evalpack={out} 会话={len(manifest['sessions'])} "
          f"交互={manifest['stats']} zip={out}.zip")
    if args.export_e2e_fixtures:
        from replay.evolve import export_e2e_fixtures
        export_e2e_fixtures(str(out), args.fixtures_dir)
    return 0


async def cmd_replay(args: argparse.Namespace) -> int:
    from replay import runner
    judge_cfg = None
    if args.judge:
        from graders.correctness import judge_from_env
        judge_cfg = judge_from_env()
        if judge_cfg is None:
            print("[judge] 跳过（未配置 EVAL_LLM_*）")

    if args.provision:
        # M2 provision pack 化：evalpack → 独立存储 + 受测 jar（docker）+ 三回放器
        from provision import replay_pack
        result = replay_pack.provision_replay(
            repo_dir=REPO_DIR, eval_dir=EVAL_DIR, pack_dir=args.pack, out_dir=args.out,
            mode=args.mode, judge_cfg=judge_cfg,
            case_ids=(args.only.split(",") if args.only else None))
        return result["exit_code"]

    if args.rebase:
        # 基线演进：loose 回放（不阻断）→ 捕获实际请求 → 生成新基线包（响应保持录制件）
        from replay import evolve
        proc, llm_port = runner.start_replay_llm(
            args.pack, [r for r in (args.rewrite or "").split(",") if "=" in r])
        try:
            code = await runner.run_replay(
                pack_dir=args.pack, out_dir=args.out,
                base_url=args.base_url or _default_base_url(), mode="loose",
                case_ids=(args.only.split(",") if args.only else None),
                judge_cfg=judge_cfg, replayer=(proc, llm_port))
            new_pack = evolve.rebase_pack(args.pack, llm_port, args.rebase,
                                          note=args.rebase_note or "")
        finally:
            proc.terminate()
        return 0 if new_pack.exists() and code in (0, 1) else 1

    rewrites = [r for r in (args.rewrite or "").split(",") if "=" in r]
    return await runner.run_replay(
        pack_dir=args.pack, out_dir=args.out, base_url=args.base_url or _default_base_url(),
        mode=args.mode, case_ids=(args.only.split(",") if args.only else None),
        judge_cfg=judge_cfg, rewrites=rewrites, keep_replayer=args.keep_replayer)


async def cmd_live(args: argparse.Namespace) -> int:
    from replay import runner
    judge_cfg = None
    if args.judge:
        from graders.correctness import judge_from_env
        judge_cfg = judge_from_env()
        if judge_cfg is None:
            print("[judge] 跳过（未配置 EVAL_LLM_*）")
    return await runner.run_live(
        base_url=args.base_url or _default_base_url(), out_dir=args.out,
        pack_dir=args.pack, cases=_load_library() if args.cases else None,
        case_ids=(args.only.split(",") if args.only else None),
        judge_cfg=judge_cfg, timeout_s=args.timeout)


# ---------------------------------------------------------------------------
# 导出（评测数据 → 开源评测生态）：deepeval JSONL / langfuse dataset items。
# 分工：数据收集归本平台，评测执行交外部工具；导出物零密钥，id 确定性保证幂等。
# ---------------------------------------------------------------------------

def cmd_export(args: argparse.Namespace) -> int:
    from exporters import deepeval as deepeval_exp, langfuse_client, sources

    if args.target == "langfuse-scores":
        return _export_langfuse_scores(args, langfuse_client)

    if args.kind == "testcases" and not args.run:
        print("[export] --kind testcases 需要 --run <run 产物目录>（traces 对齐库内用例取输入）")
        return 2
    if args.run and args.kind != "testcases":
        print("[export] --run 来源仅支持 --kind testcases（run 轨迹的导出语义是已执行用例）")
        return 2

    if args.target == "cases-legacy":
        # cases/ 兼容投影再生：DatasetHub active items → legacy 用例文件（收编后唯一合法更新方式）
        out = Path(args.out or CASES_DIR)
        out.mkdir(parents=True, exist_ok=True)
        cases = _load_library()
        n = 0
        for case in cases:
            clean = {k: v for k, v in case.items() if not k.startswith("_") and k != "turns"}
            with open(out / f"{case['case_id']}.json", "w", encoding="utf-8") as f:
                json.dump(clean, f, ensure_ascii=False, indent=1)
            n += 1
        note = out / "README.md"
        if not note.exists():
            note.write_text("# 兼容投影（勿手改）\n\n"
                            "本目录为 DatasetHub（bench/eval/datasets/）的 cases-legacy 投影，"
                            "由 `flywheel.py export --target cases-legacy` 再生。\n"
                            "改用例请改数据集 item 或走人审流程。\n", encoding="utf-8")
        print(f"[export] cases-legacy 投影: {n} 条 → {out}")
        return 0

    if not args.out:
        print("[export] --out 必填（仅 cases-legacy 可缺省投影回 bench/eval/cases）")
        return 2

    if args.pack:
        items = sources.load_from_pack(args.pack)
        with open(Path(args.pack) / "manifest.json", encoding="utf-8") as f:
            pack_id = json.load(f).get("pack_id") or Path(args.pack).name
        id_prefix = args.id_prefix or pack_id  # 跨包同 sid 不撞 langfuse 项目级唯一 id
    elif args.run:
        items = sources.load_from_run(args.run, CASES_DIR)
        id_prefix = args.id_prefix
    else:
        # 缺省来源 = DatasetHub active items（收编统一后 cases/ 仅为投影）
        from dataset import load_items
        items = []
        for it in load_items(args.hub_service, args.hub_name, "active"):
            meta = dict(it.get("metadata") or {})
            meta["case_id"] = it["id"]  # 外部格式按 metadata.case_id 溯源（deepeval 白名单）
            entry = {
                "id": it["id"],
                "input_message": (it.get("input") or {}).get("message", ""),
                "turns": it.get("turns"),
                "ground_truth": it.get("ground_truth"),
                "expected_tools": meta.get("requires_tools"),
                "final_output": None,
                "actual_output": None,
                "metadata": meta,
            }
            if entry["input_message"]:
                items.append(entry)
        id_prefix = args.id_prefix
    if not items:
        print("[export] 来源无可用条目（pack 会话须含用户输入；run 须有能对上库内用例的 traces）")
        return 2

    out = Path(args.out)
    if args.target == "deepeval":
        produced = deepeval_exp.export(items, out, kind=args.kind)
        for p in produced:
            with open(p, encoding="utf-8") as f:
                n = sum(1 for line in f if line.strip())
            print(f"[export] deepeval {p.name}: {n} 行")
        if produced:
            print("[export] 加载：EvaluationDataset().add_goldens_from_jsonl_file(file_path=...)"
                  "（多轮行带 scenario，deepeval 自动识别为 conversational）")
    else:
        if not args.dataset:
            print("[export] --target langfuse 需要 --dataset <名称>")
            return 2
        summary = langfuse_client.export(items, out, args.dataset,
                                         id_prefix=id_prefix, do_push=args.push)
        print(f"[export] langfuse 本地: {summary['local']}（{summary['items']} 条）")
        if args.push:
            pushed = summary["push"]
            print(f"[export] langfuse push: upserted={pushed['upserted']}/{pushed['total']}"
                  f" → {args.dataset}")
            for f in pushed["failed"]:
                print(f"  [FAIL] {f['id']}: {f['detail']}")
            if pushed["failed"]:
                return 1
    return 0


def _locate_dataset(args: argparse.Namespace) -> tuple[str, str]:
    """按 --service/--name/--promote(或 --archive) 的 item id 定位所属数据集。"""
    from dataset import load_items
    item_id = args.promote or args.archive
    for it in load_items(args.service, args.name, None):
        if it["id"] == item_id:
            svc, name = it["metadata"]["_dataset"].split("/", 1)
            return svc, name
    raise SystemExit(f"[dataset] 未找到 item: {item_id}")


def _export_langfuse_scores(args: argparse.Namespace, langfuse_client: Any) -> int:
    """M3 scores 回流：run 产物（traces + task.json judge_results）→ langfuse scores。

    checks 结论与 judge 分数按 traceId 挂分（一个评测会话可能对应多条 langfuse trace，
    全部同分）；trace 定位 = 评测 session_id ↔ OTel attribute agentscope.session.id
    （或 langfuse 原生 sessionId）。id 确定性 → 重跑同项覆盖旧分。
    """
    if not args.run:
        print("[export] langfuse-scores 需要 --run <run 产物目录>")
        return 2
    run_dir = Path(args.run)
    with open(run_dir / "task.json", encoding="utf-8") as f:
        task = json.load(f)
    judge_results = task.get("judge_results") or {}
    judge_model = task.get("judge_model")

    # 每用例取 repeat#1 轨迹（session_id / checks_passed / langfuse_trace_id）
    per_case: dict[str, dict[str, Any]] = {}
    for p in sorted((run_dir / "traces").glob("*.json")):
        t = json.loads(p.read_text(encoding="utf-8"))
        if t.get("repeat_no") != 1 or t["case_id"] in per_case:
            continue
        per_case[t["case_id"]] = {"session_id": t.get("session_id"),
                                  "checks": t.get("checks_passed"),
                                  "langfuse_trace_id": t.get("langfuse_trace_id")}
    if not per_case:
        print("[export] run 无 traces")
        return 2

    payloads: list[dict[str, Any]] = []
    for case_id, info in sorted(per_case.items()):
        if info["checks"] is None:
            continue
        payloads.append({"case_id": case_id, "session_id": info["session_id"],
                         "langfuse_trace_id": info.get("langfuse_trace_id"),
                         "name": f"eval-checks/{case_id}",
                         "value": 1.0 if info["checks"] else 0.0,
                         "comment": f"确定性断言 {'PASS' if info['checks'] else 'FAIL'}",
                         "meta": {"case_id": case_id, "session_id": info["session_id"],
                                  "commit": task.get("commit"), "kind": "checks"}})
        jr = judge_results.get(case_id) or {}
        if jr.get("score") is not None:
            payloads.append({"case_id": case_id, "session_id": info["session_id"],
                             "name": f"eval-judge/{case_id}", "value": jr["score"],
                             "comment": (jr.get("reason") or "")[:500],
                             "meta": {"case_id": case_id, "kind": "judge",
                                      "judge_model": judge_model,
                                      "commit": task.get("commit")}})
    out = Path(args.out or str(run_dir))
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "langfuse-scores.jsonl", "w", encoding="utf-8") as f:
        for p in payloads:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    print(f"[export] langfuse-scores 本地: {out / 'langfuse-scores.jsonl'}（{len(payloads)} 条）")
    if not args.push:
        return 0

    base, pk, sk = langfuse_client.resolve_creds()
    upserted, failed, unmatched = 0, [], []
    for p in payloads:
        # 首选 traceparent 关联（评测请求注入，traceId 客户端确定性）；无则回退
        # 按 sessionId 查询（OTel attribute 兜底路径）
        if p.get("langfuse_trace_id"):
            trace_ids = [p["langfuse_trace_id"]]
        else:
            try:
                traces = langfuse_client.find_traces(base, pk, sk, p["session_id"])
            except langfuse_client.LangfuseError as e:
                print(f"[export][FAIL] trace 查询失败: {e}")
                return 1
            trace_ids = [t["id"] for t in traces]
        if not trace_ids:
            unmatched.append(f"{p['case_id']}({p['session_id'][:20]}…)")
            continue
        result = langfuse_client.push_scores(base, pk, sk, [
            langfuse_client.build_score(tid, p["name"], p["value"],
                                        comment=p["comment"], metadata=p["meta"])
            for tid in trace_ids])
        upserted += result["upserted"]
        failed.extend(result["failed"])
    print(f"[export] scores 回流: upserted={upserted} failed={len(failed)}"
          f" unmatched_sessions={len(unmatched)}")
    for f_ in failed[:5]:
        print(f"  [FAIL] {f_['name']}: {f_['detail']}")
    for u in unmatched[:5]:
        print(f"  [WARN] 无匹配 trace（OTel 未上报或不在时间窗）: {u}")
    return 1 if failed else 0


def cmd_dataset(args: argparse.Namespace) -> int:
    import glob as _glob
    from dataset import (DatasetError, item_from_case, item_from_skeleton,
                         list_datasets, load_items, set_status, upsert_item)

    try:
        if args.list:
            for m in list_datasets():
                stats = " ".join(f"{k}={v}" for k, v in m.get("stats", {}).items())
                print(f"{m['_dir']:46s} {stats}")
            return 0
        if args.import_cases:
            # 一次性迁移：legacy cases → 种子数据集（source=manual，status=active）
            name = args.name or "regression"
            n = 0
            for case in suite_runner.load_cases(CASES_DIR):
                upsert_item(args.service, name,
                            item_from_case(case, source="manual",
                                           status=args.status or "active"),
                            changelog_note=f"import-cases {case['case_id']}")
                n += 1
            print(f"[dataset] cases 导入 {n} 条 → {args.service}/{name}（{args.status or 'active'}）")
            return 0
        if args.import_files:
            name = args.name or "imports"
            n = 0
            for p in sorted(_glob.glob(args.import_files)):
                data = json.loads(Path(p).read_text(encoding="utf-8"))
                if "case_id" not in data:
                    print(f"[dataset][WARN] 跳过非用例形态文件: {p}")
                    continue
                upsert_item(args.service, name,
                            item_from_case(data, source="import",
                                           status=args.status or "draft"),
                            changelog_note=f"import-files {data['case_id']}")
                n += 1
            print(f"[dataset] 文件导入 {n} 条 → {args.service}/{name}（{args.status or 'draft'}）")
            return 0
        if args.import_skeleton:
            # T2 骨架 → 采样转 draft：指纹去重 + limit 上限（sample 语义内置）
            from replay import normalize as nz
            name = args.name or "skeleton"
            status = args.status or "draft"
            seen: set[str] = set()
            n = 0
            for p in sorted(Path(args.import_skeleton).glob("*.json")):
                session = json.loads(p.read_text(encoding="utf-8"))
                first = (session.get("turns") or [{}])[0].get("input", "")
                if not first:
                    continue
                fp = nz.normalize_text(first)
                if fp in seen:
                    continue
                if n >= args.limit:
                    break
                seen.add(fp)
                upsert_item(args.service, name, item_from_skeleton(session, status=status),
                            changelog_note=f"import-skeleton {session['sid'][:32]}")
                n += 1
            print(f"[dataset] 骨架导入 {n} 条 → {args.service}/{name}（{status}，去重后）")
            return 0
        # promote / archive
        svc, name = _locate_dataset(args)
        item_id = args.promote or args.archive
        target = "active" if args.promote else "archived"
        set_status(svc, name, item_id, target)
        print(f"[dataset] {svc}/{name}/{item_id}: → {target}")
        return 0
    except DatasetError as e:
        print(f"[dataset][FAIL] {e}")
        return 1


def cmd_skeleton(args: argparse.Namespace) -> int:
    from skeleton import SkeletonError, export_skeleton
    try:
        produced = export_skeleton(
            args.db_host, args.db_port, args.db_user, args.db_name, args.out,
            since=args.since,
            kubectl_resource=(args.kubectl_resource if args.via == "kubectl" else None),
            kubectl_ns=args.kubectl_ns, kubectl_user=args.kubectl_user,
            kubectl_password_env=args.kubectl_password_env)
    except SkeletonError as e:
        print(f"[skeleton][FAIL] {e}")
        return 2
    print(f"[skeleton] 导出 {len(produced)} 个会话骨架 → {args.out}")
    if produced:
        print("[skeleton] 下一步: dataset --import-skeleton <out> --status draft（人审转正后进执行链路）")
    return 0


async def cmd_synth(args: argparse.Namespace) -> int:
    """G3 轨迹合成 golden：骨架素材 → LLM 蒸馏 → 四道闸门 → DatasetHub draft。"""
    from casegen.synthesizer import build_material, synthesize_goldens
    from dataset import DEFAULT_SERVICE, item_from_case, load_active_cases, upsert_item

    cfg = None
    from graders.correctness import judge_from_env
    cfg = judge_from_env()
    if cfg is None:
        print("[synth] 跳过（需要 EVAL_LLM_*）")
        return 2
    sessions = []
    for p in sorted(Path(args.skeleton).glob("*.json")):
        sessions.append(json.loads(p.read_text(encoding="utf-8")))
    material = build_material(sessions, args.limit)
    if not material:
        print("[synth] 素材为空（会话需含用户输入与终答）")
        return 2
    existing = [c["input"]["message"] for c in load_active_cases()]
    try:
        candidates = await synthesize_goldens(material, existing, cfg)
    except RuntimeError as e:
        print(f"[synth][FAIL] {e}")
        return 1
    # 闸门③：动态冒烟（可选）——跑不通的候选淘汰
    if args.base_url:
        capabilities = await suite_runner.fetch_capabilities(args.base_url)
        survivors = []
        for c in candidates:
            t = await suite_runner.execute_once(c, args.base_url, repeat_no=1)
            if t["status"] == "success":
                survivors.append(c)
            else:
                print(f"[synth] 冒烟淘汰 {c['case_id']}: {t['status']}")
        candidates = survivors
    # 闸门④：draft 落库（人审转正才进执行链路）
    for c in candidates:
        upsert_item(DEFAULT_SERVICE, args.name, item_from_case(c, source="g3_synth",
                                                              status="draft"),
                    changelog_note=f"synth origin={c['origin']['source_session'][:32]}")
    print(f"[synth] 合成 {len(candidates)} 条 golden → {DEFAULT_SERVICE}/{args.name}（draft）")
    return 0


def cmd_selftest(_: argparse.Namespace) -> int:
    """离线自检：帧映射解析 + 确定性检查器，不依赖网络与被测服务。"""
    mapping = sse_client.MAPPING
    assert "REQUIRE_USER_CONFIRM" in mapping["sdk_frames"]
    assert "permission_ask" in mapping["synthetic_frames"]

    events = [
        {"t_ms": 0, "type": "session_created", "raw": {"type": "session_created", "session_id": "s1"}},
        {"t_ms": 5, "type": "AGENT_START", "raw": {"type": "AGENT_START"}},
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
    # 首帧/启动延迟字段（issue #87）：first_event 排除 session_created
    assert view["first_event_ms"] == 5, view["first_event_ms"]
    assert view["agent_start_ms"] == 5, view["agent_start_ms"]

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
    # 挂起判定（issue #86）：不依赖终帧，按内容配对——旧序（permission_ask 终止）下挂起
    assert hview["hitl"]["pending"] is True, hview["hitl"]

    # 4db16ba+ 新序（2026-10-01 契约 v2）：ask 段以 AGENT_END 自然收尾，
    # 终帧不再是 permission_ask，但 hitl.pending 仍须判定挂起
    # （基线剔除普通轮的 AGENT_END@50/done@60：ask 主段自身以 AGENT_END 收尾一次）
    ask_main = [e for e in events if e["type"] not in ("done", "AGENT_END")] + [
        {"t_ms": 60, "type": "TOOL_CALL_START",
         "raw": {"type": "TOOL_CALL_START", "toolName": "publish_service", "toolCallId": "c9"}},
        {"t_ms": 70, "type": "permission_ask",
         "raw": {"type": "permission_ask", "reply_id": "r1",
                 "tool_calls": [{"tool_call_id": "c9", "name": "publish_service", "input": {}}]}},
        {"t_ms": 71, "type": "REQUEST_STOP", "raw": {"type": "REQUEST_STOP"}},
        {"t_ms": 72, "type": "AGENT_RESULT", "raw": {"type": "AGENT_RESULT"}},
        {"t_ms": 73, "type": "AGENT_END", "raw": {"type": "AGENT_END"}},
    ]
    aview = sse_client.build_view(ask_main)
    assert aview["terminal"] == "AGENT_END", aview["terminal"]
    assert aview["hitl"]["pending"] is True, aview["hitl"]
    # 确认续段并入后：配对补齐 → 不再挂起；合并轨迹 2 个 AGENT_END（用例期望同步为 2）
    merged = ask_main + [
        {"t_ms": 80, "type": "USER_CONFIRM_RESULT", "raw": {"type": "USER_CONFIRM_RESULT"}},
        {"t_ms": 90, "type": "TOOL_RESULT_END",
         "raw": {"type": "TOOL_RESULT_END", "toolCallId": "c9", "state": "SUCCESS"}},
        {"t_ms": 95, "type": "AGENT_END", "raw": {"type": "AGENT_END"}},
    ]
    mview = sse_client.build_view(merged)
    assert mview["hitl"]["pending"] is False, mview["hitl"]
    assert mview["hitl"]["confirmed"] is True, mview["hitl"]
    assert mview["frame_counts"]["AGENT_END"] == 2, mview["frame_counts"]
    r_merged = checks_mod.evaluate({
        "frames": {"permission_ask": 1, "AGENT_END": 2, "error": 0},
        "tool_result": {"publish_service": {"ok": True}},
    }, mview)
    assert all(c["passed"] for c in r_merged), r_merged
    # 恢复身份异常兜底：ask 存在但终帧非法（error）→ 判定仍挂起（auto_confirm 会尝试续段），
    # 异常终帧由 runner 记入 error_info
    bad_term = sse_client.build_view(ask_main + [
        {"t_ms": 74, "type": "error", "raw": {"type": "error", "error": "boom"}}])
    assert bad_term["hitl"]["pending"] is True and bad_term["terminal"] == "error"

    # 启动排队类（issue #87）：timeout + AGENT_START 缺失/过晚 → 环境排队而非用例失败；
    # 必须先于关键词规则（超时文案含 timeout/超时，旧逻辑会误归模型交互类）
    q = rca_mod.classify_failure({"status": "timeout", "error_info": "整体超时（>120s）",
                                  "view": {"error_frames": [], "agent_start_ms": None},
                                  "check_results": []})
    assert q["category"] == "启动排队类", q
    q2 = rca_mod.classify_failure({"status": "timeout", "error_info": "整体超时（>120s）",
                                   "view": {"error_frames": [], "agent_start_ms": 169000},
                                   "check_results": []})
    assert q2["category"] == "启动排队类", q2
    q3 = rca_mod.classify_failure({"status": "timeout", "error_info": "整体超时（>120s）",
                                   "view": {"error_frames": [], "agent_start_ms": 800},
                                   "check_results": []})
    assert q3["category"] == "模型交互类", q3  # 启动正常的长超时仍归模型/环境类

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

    # ---- 离线评测链路（M2 验收③）：归一化规则表 + 轨迹等价评估 ----
    # 规则与 mock/replay-llm.mjs、mock/replay-http.mjs 内置 JS 版本同构，
    # 两侧改动必须同步——本段断言即同步钉；gw-hash 为框架 AgentStateStore ID 形态。
    from replay import normalize as nz
    from replay import trajectory as traj_mod

    assert nz.normalize_text("at 2026-10-04T07:23:06.770Z ok") == "at <TS> ok"
    assert nz.normalize_text("id=1791098087123 end") == "id=<TS_MS> end"
    assert nz.normalize_text("uuid 550e8400-e29b-41d4-a716-446655440000!") == "uuid <UUID>!"
    assert nz.normalize_text("AgentStateStore ID: gw-3f146047e5fd") == "AgentStateStore ID: <GW>"
    assert nz.normalize_text("http://127.0.0.1:18902/v1") == "<HOST>/v1"
    assert nz.normalize_text("tok abcdef0123456789abcdef0123456789 end") == "tok <TOKEN> end"
    assert nz.normalize_text("max_tokens 1024") == "max_tokens 1024"  # 普通数字不受影响
    fp1 = nz.request_fingerprint({"messages": [{"role": "user", "content": "hi 2026-01-01T00:00:00Z"}]})
    assert fp1 == nz.request_fingerprint({"messages": [{"role": "user", "content": "hi 2027-01-01T00:00:00Z"}]})
    assert fp1 != nz.request_fingerprint({"messages": [{"role": "user", "content": "other"}]})

    stats_ok = {"sessions": {"s1": {"calls": 8, "exact": 7, "normalized": 1, "drift": 0, "background": 1}}}
    t_ok = traj_mod.evaluate_trajectory("s1", stats_ok, expected_llm_calls=8)
    assert t_ok["step_status"] == "pass" and t_ok["drift_rate"] == 0.0, t_ok
    stats_bad = {"sessions": {"s1": {"calls": 8, "exact": 6, "normalized": 1, "drift": 1, "background": 0}}}
    t_bad = traj_mod.evaluate_trajectory("s1", stats_bad, expected_llm_calls=8)
    assert t_bad["step_status"] == "fail" and t_bad["drift_rate"] == 0.125, t_bad
    assert traj_mod.run_drift_rate([t_ok, t_bad]) == 0.0625

    from replay.packager import final_text_from_chunks, pack, usage_from_chunks, verify_checksums
    chunks = [json.dumps({"choices": [{"delta": {"content": "你好"}}]}),
              json.dumps({"choices": [{"delta": {"content": "，世界"}}]}), "[DONE]"]
    assert final_text_from_chunks(chunks) == "你好，世界"
    u = usage_from_chunks([json.dumps({"usage": {"prompt_tokens": 3, "completion_tokens": 2,
                                                 "total_tokens": 5}})])
    assert u == {"input": 3, "output": 2, "total": 5}

    # ---- issue #97：客户端可控标识符校验（pack_id/sid/case_id 路径穿越防线） ----
    from replay.ids import InvalidIdentifierError, validate_id

    # 存量合法 id 全集（pack/sid/case 三族形态）必须全部通过
    valid_ids = [
        "pk-e2e-demo", "pk-gold", "pk-rebased", "pk-demo-round-20261004-122540",
        "sess-rec-1", "sess-collector-direct", "sess-http-1", "gold-rec-1",
        "auto-0001-llm-3f1d2e4a",                      # 指纹聚类合成 sid
        "case_replay_sess-rec-1", "case_chat_basic_001",
        "e2e", "rec",                                  # from-collector 的 ns 形态
        "a" * 128,                                     # 长度上边界
    ]
    for v in valid_ids:
        assert validate_id("test", v) == v, v
    # 非法形态：穿越/分隔符/坏首字符/越界/非字符串/串尾换行（re $ 陷阱，评审发现）
    invalid_ids = ["../x", "/etc/cron.d", "a..b", "a/b", "a\\b", "..", ".", "-lead",
                   "sess rec", "sess:rec", "", "a" * 129, None, 123, "a\n", "a\r\n"]
    for v in invalid_ids:
        try:
            validate_id("test", v)
            raise AssertionError(f"validate_id 应拒绝: {v!r}")
        except InvalidIdentifierError:
            pass

    # ---- issue #97：verify_checksums 三重校验（正向/清单 rel 防穿越/反向扫描） ----
    import hashlib
    import tempfile

    with tempfile.TemporaryDirectory() as td:
        td_path = Path(td)

        def _mk_pack(name: str, checksums: str, extra: dict[str, str] | None = None) -> Path:
            d = td_path / name
            d.mkdir(parents=True)
            (d / "a.txt").write_text("hello", encoding="utf-8")
            (d / "CHECKSUMS").write_text(checksums, encoding="utf-8")
            for fname, content in (extra or {}).items():
                fp = d / fname
                fp.parent.mkdir(parents=True, exist_ok=True)
                fp.write_text(content, encoding="utf-8")
            return d

        sha_hello = hashlib.sha256(b"hello").hexdigest()
        assert verify_checksums(_mk_pack("ok", f"{sha_hello}  a.txt\n")) is True
        d = _mk_pack("tampered", f"{sha_hello}  a.txt\n")
        (d / "a.txt").write_text("evil", encoding="utf-8")
        assert verify_checksums(d) is False                                    # 内容与清单不符
        assert verify_checksums(
            _mk_pack("unregistered", "", extra={"evil.json": "{}"})) is False  # 空清单+多余文件漏过（原缺陷）
        assert verify_checksums(_mk_pack(
            "nested-checksums", f"{sha_hello}  a.txt\n",
            extra={"sessions/CHECKSUMS": "payload"})) is False  # 子目录未登记同名 CHECKSUMS 不豁免（评审发现）

        # 生成/校验豁免口径对齐（评审发现）：oaf_zip 恰名为 CHECKSUMS 时（copy 到
        # out/oaf/CHECKSUMS），自产包登记它之后必须仍能过自家 verify_checksums
        src = td_path / "collector" / "e2e" / "llm"
        src.mkdir(parents=True)
        rec = {"id": "llm-abcdef01", "session": "sess-ok", "ts_start": "2026-10-09T00:00:00Z",
               "request": {"messages": [{"role": "user", "content": "hi"}]},
               "chunks": [json.dumps({"choices": [{"delta": {"content": "yo"}}]}), "[DONE]"]}
        (src / "20261009.jsonl").write_text(json.dumps(rec) + "\n", encoding="utf-8")
        oaf = td_path / "CHECKSUMS"
        oaf.write_text("oaf-content", encoding="utf-8")
        pk = pack(collector_dir=str(td_path / "collector"), out_dir=str(td_path / "pk-roundtrip"),
                  ns="e2e", oaf_zip=str(oaf))
        assert (pk / "oaf" / "CHECKSUMS").exists()
        assert verify_checksums(pk) is True
        d = td_path / "missing"
        d.mkdir()
        (d / "a.txt").write_text("x", encoding="utf-8")
        assert verify_checksums(d) is False                                    # 缺 CHECKSUMS 不抛（原缺陷）
        outside = td_path / "outside.json"
        outside.write_text("{}", encoding="utf-8")
        assert verify_checksums(_mk_pack(
            "abs-rel", f"{hashlib.sha256(outside.read_bytes()).hexdigest()}  {outside}\n")) is False
        assert verify_checksums(
            _mk_pack("dotdot-rel", f"{sha_hello}  ../ok/a.txt\n")) is False    # 清单 rel 穿越出包
        assert verify_checksums(
            _mk_pack("bad-line", f"{sha_hello} a.txt\n")) is False             # 坏行（缺双空格）判失败

    # issue #39：能力门禁跳过分类（kind）与报告渲染——capability 单列醒目、env 预期分流。
    # 两条用例全部被跳过 → runnable 为空，run_suite 不发起任何网络请求（离线可测）。
    async def _skip_probe() -> None:
        skip_cases = [
            {"case_id": "case_skip_tool", "title": "t", "category": "c",
             "input": {"message": "hi"}, "expected": {}, "requires_tools": ["read_file"]},
            {"case_id": "case_skip_env", "title": "t", "category": "c",
             "input": {"message": "hi"}, "expected": {}, "requires_env": ["reload"]},
        ]
        _, skipped = await suite_runner.run_suite(
            skip_cases, "http://127.0.0.1:1", repeat=1, capabilities={"echo"}, env_tags=set())
        kinds = {s["case_id"]: s.get("kind") for s in skipped}
        assert kinds == {"case_skip_tool": "capability", "case_skip_env": "env"}, skipped
        rep = rca_mod.build_report("selftest-skip", {}, [], skipped)
        assert "⚠️ 能力缺失" in rep and "case_skip_tool" in rep, rep
        assert "环境未供给" in rep and "case_skip_env" in rep, rep

    asyncio.run(_skip_probe())

    # issue #97 问题c：collector 自研 YAML 解析器回归断言（抽取 server.mjs 真实实现执行，
    # 对 example.yaml 每键断言类型与值——防止行尾注释/列表项静默失效回归）
    yaml_script = EVAL_DIR / "tests" / "collector_yaml_selftest.mjs"
    try:
        r = subprocess.run(["node", str(yaml_script)], capture_output=True, text=True, timeout=60)
    except FileNotFoundError as e:
        raise AssertionError("collector YAML 断言需要 node（CI runner 预装；本机请自装 Node ≥ 18）") from e
    assert r.returncode == 0, f"collector YAML 断言失败:\n{r.stdout[-800:]}\n{r.stderr[-800:]}"

    # ---- usage_frame token 口径（e1551b7）：恒等式 + 全量 prompt 语义 ----
    # 契约 note 声明 inputTokens 为全量 prompt、outputTokens 为全量 completion，
    # 恒等式 inputTokens + outputTokens === totalTokens。跨运行时对比与成本核算按此
    # 口径聚合；若上报「缓存未命中增量」会随缓存命中率升高趋近 0（dsh issue #7 成因）。
    # 字段映射被改键或聚合口径漂移在此红。
    usage_frame = sse_client.MAPPING["usage_frame"]
    usage_events = [
        {"t_ms": 10, "type": usage_frame["type"],
         "raw": {"type": usage_frame["type"], "inputTokens": 100, "outputTokens": 20, "totalTokens": 120}},
        {"t_ms": 30, "type": usage_frame["type"],
         "raw": {"type": usage_frame["type"], "inputTokens": 7, "outputTokens": 3, "totalTokens": 10}},
    ]
    uview = sse_client.build_view(usage_events)
    assert uview["token_usage"] == {"input": 107, "output": 23, "total": 130}, uview["token_usage"]
    assert uview["token_usage"]["input"] + uview["token_usage"]["output"] \
        == uview["token_usage"]["total"], "usage_frame 恒等式被破坏"
    # 单帧恒等式（口径声明的最小校验形态）：每帧也必须满足 input+output===total
    for ue in usage_events:
        raw = ue["raw"]
        assert raw["inputTokens"] + raw["outputTokens"] == raw["totalTokens"]

    # ---- issue #117：judge 输出畸形 JSON 必须降级 JudgeUnavailable，不得裸抛 ----
    from graders.correctness import JudgeUnavailable, parse_judge_verdict

    ok_verdict = parse_judge_verdict('{"score": 0.85, "reason": "正确"}')
    assert ok_verdict == {"score": 0.85, "reason": "正确"}, ok_verdict
    # reasoning 模型常在 JSON 外带思考文本 → 走 {...} 提取路径
    wrapped = parse_judge_verdict('思考中……最终结论：\n{"score": 1, "reason": "一致"}\n以上。')
    assert wrapped["score"] == 1.0 and wrapped["reason"] == "一致", wrapped
    # 钳位
    assert parse_judge_verdict('{"score": 5, "reason": "越界"}')["score"] == 1.0
    assert parse_judge_verdict('{"score": -2}')["score"] == 0.0
    # 畸形输出矩阵：截断/多对象/尾逗号/纯文本/非 dict JSON/score 非数字/NaN/None → JudgeUnavailable（不是裸异常）
    for bad in ['{"score": 0.5, "reason": "截断', '{"score": 0.5} {"score": 0.6}',
                '{"score": 0.5, "reason": "尾逗号",}', '完全不是 JSON',
                '{"score": "不是数字", "reason": "r"}',
                '[1, 2, 3]', '"just a string"', 'null', '42',
                '{"score": NaN, "reason": "r"}',   # json.loads 接受 NaN，钳位会反拿满分（review 发现）
                None]:
        try:
            parse_judge_verdict(bad)
            raise AssertionError(f"畸形输出应抛 JudgeUnavailable: {bad!r}")
        except JudgeUnavailable:
            pass
    # ±Infinity 钳位语义（inf→1.0 / -inf→0.0）
    assert parse_judge_verdict('{"score": Infinity}')['score'] == 1.0
    assert parse_judge_verdict('{"score": -Infinity}')['score'] == 0.0

    # ---- 导出器契约（dataset hub M1）：schema/单多轮拆分/确定性 id/凭据零泄漏 ----
    from exporters import deepeval as exp_deepeval, langfuse_client as exp_lf, sources

    lib_items = sources.load_from_cases(CASES_DIR)
    assert lib_items and len({i["id"] for i in lib_items}) == len(lib_items)
    assert all(i["input_message"] for i in lib_items), "库内用例不应出现空输入"
    assert all(i["metadata"].get("category") for i in lib_items)

    synth_multi = {"id": "case_replay_sessdemo", "input_message": "第一轮问询",
                   "turns": [{"input": "第一轮问询"}, {"input": "追问"}],
                   "ground_truth": "录制终答", "expected_tools": ["echo"],
                   "final_output": "录制终答", "actual_output": None,
                   "metadata": {"case_id": "case_replay_sessdemo", "source_kind": "pack"}}
    single, conv = exp_deepeval.render_goldens(lib_items + [synth_multi])
    assert single and all("input" in l and "scenario" not in l and "expected_output" in l
                          for l in single), "单轮行 schema 被破坏"
    assert conv and all("scenario" in l and "input" not in l for l in conv), "多轮行 schema 被破坏"
    # 不脑补：无金标的条目不得造 null 键
    bare_single, _ = exp_deepeval.render_goldens([{"id": "c0", "input_message": "q",
                                                   "turns": None, "ground_truth": None,
                                                   "expected_tools": None, "final_output": None,
                                                   "actual_output": None, "metadata": {}}])
    assert "expected_output" not in bare_single[0] and "expected_tools" not in bare_single[0]
    tc = exp_deepeval.render_testcases([
        {"id": "c1", "input_message": "q", "actual_output": "a", "ground_truth": "g",
         "expected_tools": None, "final_tools_called": ["echo"], "turns": None,
         "final_output": None, "metadata": {}},
        {"id": "c2", "input_message": "q2", "actual_output": None, "ground_truth": None,
         "expected_tools": None, "final_tools_called": None, "turns": None,
         "final_output": None, "metadata": {}}])
    assert len(tc) == 1 and tc[0]["actual_output"] == "a" \
        and tc[0]["tools_called"] == [{"name": "echo"}], tc  # deepeval ToolCall 映射形态

    lf_item = {"id": "case_x", "input_message": "hi", "turns": None, "ground_truth": "g",
               "expected_tools": None, "final_output": None, "actual_output": None,
               "metadata": {"case_id": "case_x", "category": "function", "title": None}}
    p1 = exp_lf.build_item(lf_item, "ds-a")
    assert p1 == exp_lf.build_item(lf_item, "ds-a"), "langfuse id 必须确定性（upsert 幂等前提）"
    assert p1["id"] == "case_x" and p1["datasetName"] == "ds-a"
    assert exp_lf.build_item(lf_item, "ds-a", id_prefix="pk-p")["id"] == "pk-p_case_x"
    assert p1["expectedOutput"] == {"answer": "g"}
    assert "title" not in p1["metadata"], "None 值溯源键不应透传"
    blob = json.dumps(p1)
    assert "sk-" not in blob and "LANGFUSE_" not in blob, "导出物不得携带密钥形态字段"
    with tempfile.TemporaryDirectory() as td:
        assert exp_lf.write_items(Path(td) / "items.jsonl", [p1]) == 1

    # mock langfuse 契约（鉴权/upsert 幂等/5xx 退避/4xx 快速失败）：localhost 零外联
    try:
        r = subprocess.run(["python3", str(EVAL_DIR / "tests" / "export_loop.py")],
                           capture_output=True, text=True, timeout=120)
    except FileNotFoundError as e:
        raise AssertionError("export 契约 e2e 需要 python3") from e
    assert r.returncode == 0, f"export_loop 契约失败:\n{r.stdout[-800:]}\n{r.stderr[-800:]}"

    # ---- DatasetHub（M2）：落盘/状态机/CHECKSUMS/投影等价/骨架解析/收编一致性 ----
    from dataset import (DatasetError, item_from_case, item_from_skeleton,
                         load_active_cases, load_items, set_status, to_case, upsert_item)
    from replay.packager import verify_checksums

    with tempfile.TemporaryDirectory() as td:
        base = Path(td)
        it = {"id": "case_hub_demo", "input": {"message": "hi", "userId": "u"},
              "ground_truth": "g", "expected": {"no_error": True}, "status": "draft",
              "metadata": {"title": "t", "category": "core",
                           "requires_tools": ["echo"], "timeout_s": 60}}
        upsert_item("svc-demo", "ds-demo", it, datasets_dir=base, changelog_note="seed")
        for bad in ({**it, "id": "../x"}, {**it, "status": "bogus"},
                    {k: v for k, v in it.items() if k != "input"}):
            try:
                upsert_item("svc-demo", "ds-demo", bad, datasets_dir=base)
                raise AssertionError(f"非法 item 应被拒绝: {bad.get('id', bad)}")
            except DatasetError:
                pass
        set_status("svc-demo", "ds-demo", "case_hub_demo", "active", datasets_dir=base)
        try:
            set_status("svc-demo", "ds-demo", "case_hub_demo", "draft", datasets_dir=base)
            raise AssertionError("状态回退应被拒绝")
        except DatasetError:
            pass
        # 投影等价：active item → runner 兼容用例字典（requires_tools/ground_truth 透传）
        case = to_case(load_items("svc-demo", "ds-demo", None, datasets_dir=base)[0])
        assert case["case_id"] == "case_hub_demo" and case["requires_tools"] == ["echo"]
        assert case["ground_truth"] == "g" and case["expected"] == {"no_error": True}
        # item_from_case 往返：用例字典 → item → 用例字典 关键字段不丢
        rt = to_case(item_from_case(case, source="manual", status="active"))
        assert rt["case_id"] == case["case_id"] and rt["requires_tools"] == ["echo"]
        # CHECKSUMS 篡改检测（复用 packager 三重校验）
        ddir = base / "svc-demo" / "ds-demo"
        assert verify_checksums(ddir) is True
        (ddir / "items" / "case_hub_demo.json").write_text("{}", encoding="utf-8")
        assert verify_checksums(ddir) is False

    # 骨架解析：user/assistant/tool 聚合、final 取末段助手文本、thinking 全程剔除
    from skeleton import sessions_from_rows

    rows = [
        ("s1:gw-a", "USER",
         json.dumps({"role": "USER", "content": [{"type": "text", "text": "你好"}]}),
         "2026-10-26 10:00:00"),
        ("s1:gw-a", "ASSISTANT",
         json.dumps({"role": "ASSISTANT", "content": [
             {"type": "thinking", "thinking": "内心戏不应外泄"},
             {"type": "tool_use", "id": "t1", "name": "echo"}]}),
         "2026-10-26 10:00:05"),
        ("s1:gw-a", "TOOL",
         json.dumps({"role": "TOOL", "content": [{"type": "tool_result", "name": "echo"}]}),
         "2026-10-26 10:00:06"),
        ("s1:gw-a", "ASSISTANT",
         json.dumps({"role": "ASSISTANT", "content": [{"type": "text", "text": "答复"}]}),
         "2026-10-26 10:00:07"),
    ]
    sess = sessions_from_rows(rows)["s1:gw-a"]
    assert sess["turns"] == [{"input": "你好", "ts": "2026-10-26 10:00:00"}], sess["turns"]
    assert sess["tool_names"] == ["echo"] and sess["final_output"] == "答复"
    assert sess["message_count"] == 4 and sess["ts_start"] == "2026-10-26 10:00:00"
    assert "内心戏" not in json.dumps(sess, ensure_ascii=False)
    sk_item = item_from_skeleton(sess, case_id="case_t2_demo")
    assert sk_item["status"] == "draft" and sk_item["input"]["message"] == "你好"
    assert sk_item["metadata"]["source"] == "t2_skeleton"
    assert sk_item["expected"]["tool_calls"] == {"required": ["echo"]}

    # 收编一致性（漂移即红）：git 已跟踪的 active 数据集条目 == cases/ 投影。
    # 对比口径刻意限定「git 已跟踪」——运行态数据集（gen-drafts/skeleton/imports，
    # .gitignore 忽略）自由流转不参与；投影滞后于已提交资产才算漂移。
    tracked = subprocess.run(["git", "ls-files", "bench/eval/datasets"],
                             cwd=REPO_DIR, capture_output=True, text=True, timeout=15)
    tracked_ids: set[str] = set()
    if tracked.returncode == 0:
        for f in tracked.stdout.splitlines():
            if "/items/" in f and f.endswith(".json"):
                data = json.loads((REPO_DIR / f).read_text(encoding="utf-8"))
                if data.get("status") == "active":
                    tracked_ids.add(data["id"])
    proj_ids = {c["case_id"] for c in suite_runner.load_cases(CASES_DIR)}
    assert tracked_ids == proj_ids, f"cases/ 投影与 DatasetHub 漂移: {tracked_ids ^ proj_ids}"

    print(f"selftest PASS（帧映射 + 检查器 + HITL 新旧双序挂起判定 + 启动排队分类 + 错误断言 + 跳过分类"
          f" + 归一化规则/轨迹等价（M2） + usage_frame 口径（全量 prompt 恒等式）"
          f" + collector YAML 加载断言（issue #97）+ judge 降级解析（issue #117）"
          f" + 导出器契约（deepeval/langfuse schema·确定性 id·凭据零泄漏·mock 推送幂等）"
          f" + DatasetHub（落盘/状态机/校验和/投影等价/骨架解析/收编一致性），"
          f"{len(mapping['sdk_frames'])} 枚举 + {len(mapping['synthetic_frames'])} 合成帧，契约 v{mapping['version']}）")
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

    # ---- 离线评测链路 ----
    pk = sub.add_parser("pack", help="collector 录制数据 → evalpack 数据包")
    pk.add_argument("--collector-dir", required=True, help="collector 数据目录（…/data 或 …/data/{ns}）")
    pk.add_argument("--out", required=True, help="evalpack 输出目录（如 reports/packs/pk-xxx）")
    pk.add_argument("--ns", default=None, help="只打包该 ns（collector-dir 为 data 根时使用）")
    pk.add_argument("--traces", default=None, help="driver 轨迹捕获目录（可选，提升用例草稿精度）")
    pk.add_argument("--oaf-zip", default=None, help="OAF 包 zip 副本（可选）")
    pk.add_argument("--since", default=None, help="起始时间（ISO，可选）")
    pk.add_argument("--until", default=None, help="结束时间（ISO，可选）")
    pk.add_argument("--export-e2e-fixtures", action="store_true",
                    help="同时导出 e2e/mock/fixtures/llm 夹具（入库走 PR 人审）")
    pk.add_argument("--fixtures-dir", default=None, help="夹具输出目录（缺省 pack 同级 e2e-fixtures/）")
    pk.set_defaults(func=cmd_pack)

    prp = sub.add_parser("replay", help="evalpack 离线回放：A1 断言 + A2 轨迹等价 + A3 漂移")
    prp.add_argument("--pack", required=True, help="evalpack 目录")
    prp.add_argument("--out", required=True, help="run 产物目录")
    prp.add_argument("--base-url", default=None, help="被测服务地址（其 LLM 须指向 replay-llm）")
    prp.add_argument("--mode", choices=["strict", "loose"], default="strict",
                     help="strict=轨迹失配判失败；loose=只记录漂移（默认 strict）")
    prp.add_argument("--only", default=None, help="只跑指定 case_id（逗号分隔）")
    prp.add_argument("--rewrite", default="", help="占位改写 from=to（逗号分隔多组）")
    prp.add_argument("--judge", action="store_true", help="启用语义打分（需 EVAL_LLM_*）")
    prp.add_argument("--keep-replayer", action="store_true", help="保留 replay-llm 进程供调试")
    prp.add_argument("--provision", action="store_true",
                     help="回放供给：evalpack → 独立存储 + 受测 jar（docker，需 mvn package）+ 三回放器")
    prp.add_argument("--rebase", default=None, metavar="NEW_PACK_DIR",
                     help="基线演进：loose 回放后以实际请求生成新基线包（响应保持录制件）")
    prp.add_argument("--rebase-note", default=None, help="rebase 留痕说明")
    prp.set_defaults(func=cmd_replay)

    plv = sub.add_parser("live", help="联机链路评测：用例直打真实服务 + 断言/judge")
    plv.add_argument("--base-url", default=None, help="被测服务地址（真实测试环境）")
    plv.add_argument("--pack", default=None, help="用例来源 evalpack（与 --cases 二选一）")
    plv.add_argument("--cases", action="store_true", help="用例来源 = 库内 cases/*.json")
    plv.add_argument("--out", required=True, help="run 产物目录")
    plv.add_argument("--only", default=None, help="只跑指定 case_id（逗号分隔）")
    plv.add_argument("--judge", action="store_true", help="启用语义打分（需 EVAL_LLM_*）")
    plv.add_argument("--timeout", type=float, default=120.0, help="单轮 SSE 超时秒数")
    plv.set_defaults(func=cmd_live)

    pex = sub.add_parser("export", help="评测数据导出为 deepeval / langfuse / cases-legacy（零新依赖）")
    pex.add_argument("--target",
                     choices=["deepeval", "langfuse", "cases-legacy", "langfuse-scores"],
                     required=True, help="导出目标（langfuse-scores = M3 观测回流，需 --run）")
    pex.add_argument("--kind", choices=["goldens", "testcases"], default="goldens",
                     help="deepeval 导出形态：goldens=金标集（默认）；testcases=已执行用例（需 --run）")
    pex.add_argument("--cases", action="store_true", help="来源=DatasetHub active items（缺省来源，保留兼容）")
    pex.add_argument("--hub-service", default=None, help="缺省来源限定 service 段（默认全部）")
    pex.add_argument("--hub-name", default=None, help="缺省来源限定数据集名（默认全部）")
    pex.add_argument("--pack", default=None, help="来源 evalpack 目录（多轮会话，id 前缀默认 pack_id）")
    pex.add_argument("--run", default=None, help="来源 flywheel run 产物目录（traces/*.json）")
    pex.add_argument("--dataset", default=None, help="langfuse 数据集名（--target langfuse 必填）")
    pex.add_argument("--out", default=None,
                     help="导出输出目录（cases-legacy 缺省 bench/eval/cases，其余必填）")
    pex.add_argument("--push", action="store_true",
                     help="langfuse：推送远端（需 LANGFUSE_HOST/_PUBLIC_KEY/_SECRET_KEY 环境变量）")
    pex.add_argument("--id-prefix", default=None, help="langfuse item id 前缀（缺省：pack 用 pack_id，cases 无前缀）")
    pex.set_defaults(func=cmd_export)

    pd = sub.add_parser("dataset", help="DatasetHub：清单/导入/人审状态机（唯一事实源）")
    dg = pd.add_mutually_exclusive_group(required=True)
    dg.add_argument("--list", action="store_true", help="数据集清单与状态统计")
    dg.add_argument("--import-cases", action="store_true", help="cases/ → 种子数据集（一次性迁移）")
    dg.add_argument("--import-files", metavar="GLOB", help="用例形态 JSON 文件 → 数据集")
    dg.add_argument("--import-skeleton", metavar="DIR",
                    help="skeleton 导出目录 → 指纹去重采样转 draft（sample 语义内置）")
    dg.add_argument("--promote", metavar="ITEM_ID", help="item 状态 → active（人审转正）")
    dg.add_argument("--archive", metavar="ITEM_ID", help="item 状态 → archived")
    pd.add_argument("--service", default="agent-framework", help="数据集 service 段（默认 agent-framework）")
    pd.add_argument("--name", default=None,
                    help="数据集名（import-cases 缺省 regression，import 缺省 imports/skeleton；"
                         "promote/archive 缺省按 item id 全局定位）")
    pd.add_argument("--status", default=None,
                    help="导入状态（import-cases 缺省 active，import-files/skeleton 缺省 draft）")
    pd.add_argument("--limit", type=int, default=20, help="import-skeleton 采样上限（默认 20）")
    pd.set_defaults(func=cmd_dataset)

    psk = sub.add_parser("skeleton", help="T2 checkpoint 只读导出会话骨架")
    psk.add_argument("--via", choices=["kubectl", "direct"], default="kubectl",
                     help="kubectl=exec 进 DB 容器（默认，凭据不出 pod）；direct=mysql CLI（密码走 MYSQL_PWD）")
    psk.add_argument("--kubectl-resource", default="deploy/oaf-mysql", help="kubectl exec 目标")
    psk.add_argument("--kubectl-ns", default="agent-platform")
    psk.add_argument("--kubectl-user", default="root", help="容器内 mysql 用户")
    psk.add_argument("--kubectl-password-env", default="MYSQL_ROOT_PASSWORD",
                     help="容器内保存密码的环境变量名")
    psk.add_argument("--db-name", default="oaf_checkpoint", help="checkpoint 库名")
    psk.add_argument("--db-host", default="127.0.0.1", help="MySQL 地址（direct 模式用）")
    psk.add_argument("--db-port", type=int, default=3307)
    psk.add_argument("--db-user", default="oaf")
    psk.add_argument("--out", required=True, help="骨架输出目录")
    psk.add_argument("--since", default=None, help="只导该时间之后的会话（YYYY-MM-DD[ hh:mm[:ss]]）")
    psk.set_defaults(func=cmd_skeleton)

    psy = sub.add_parser("synth", help="G3 LLM 从骨架素材合成 golden（四道闸门产 draft）")
    psy.add_argument("--skeleton", required=True, help="skeleton 导出目录（素材）")
    psy.add_argument("--base-url", default=None, help="冒烟目标（可选；不配则只过静态闸门）")
    psy.add_argument("--limit", type=int, default=10, help="素材会话上限（默认 10）")
    psy.add_argument("--name", default="gen-drafts", help="draft 落库数据集名（默认 gen-drafts）")
    psy.set_defaults(func=cmd_synth)

    args = p.parse_args()
    result = args.func(args)
    return asyncio.run(result) if asyncio.iscoroutine(result) else result


if __name__ == "__main__":
    sys.exit(main())
