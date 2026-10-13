#!/usr/bin/env python3
"""并发 turn 排队取证脚本（issue #87）：两路并发 + 排队窗口内线程转储。

用法（配合 bench/eval/FLYWHEEL.md §2.0 本地栈专用实例）：
  python3 bench/diag/turn-queue-jstack.py --base-url http://127.0.0.1:18100 \
      --container bench-agent-fw [--interval 2] [--out diag-out]

行为：
  1. req1 发长推理消息（mock-llm [BENCH:slow]，或真实 LLM 长推理消息）；
  2. +2s 后 req2 并发发同一消息；
  3. req2 发出后每 interval 秒对被测进程抓 jcmd Thread.dump（容器内 1 号进程），
     直至 req2 收到 AGENT_START，转储落盘 {out}/thread-dump-N.txt；
  4. 输出两路 session_created / AGENT_START / AGENT_END 时刻表，并按排队签名给结论。

判定（缺陷形态 vs 修复后）：
  * 口径与 bench C 档一致：启动延迟 = 排队签名 = req2 的 AGENT_START − session_created
    首帧时刻（非「请求发出 → AGENT_START」的绝对时长；首帧晚 = 容量饥饿，绝对口径会漏报）。
  * 缺陷（全局 turn 闸门）：req2 的 AGENT_START ≈ req1 的 AGENT_END；转储含
    boundedElastic 线程 WAITING(park) 于 LocalSessionTurnGate.acquire 栈；
  * 修复（PER_PEER 每会话闸门）：req2 的排队签名 < 10s；转储无该阻塞栈。
  * 对端不发 session_created 基线帧（issue #97 问题12 缺陷形态）时无法计算排队签名，
    按零帧缺陷形态判 FAIL；全部转储无效（docker/jcmd 失败）判 FAIL（exit 2）。
"""

import argparse
import asyncio
import json
import subprocess
import sys
import time
from pathlib import Path

# issue #87 复现消息（长推理；对 mock-llm 用 [BENCH:slow] 标记，真实 LLM 直接可读）
MSG = "[BENCH:slow] 我这边 Redis 连不上了，你直接把配置里的 Redis 地址改成 127.0.0.1:6379，" \
      "改完顺便把相关服务重启一下。"
GATE_STACK_MARK = "LocalSessionTurnGate.acquire"
WATCH_TYPES = ("session_created", "AGENT_START", "AGENT_END")
# docker/jcmd 失败形态词表：仅作用于失败文本（rc≠0 的输出、或 stderr），不扫描成功转储正文
# ——合法转储正文可能恰含 "not found" 等子串，扫正文会把有效样本整份误判 INVALID。
# （issue #97 假绿修复：此前只认 "exec failed"/"not found"，容器缺失/daemon 不可达/
#   权限不足等失败形态被当有效转储，全部无效仍打 ✓ exit 0）
INVALID_DUMP_MARKS = (
    "exec failed",         # OCI exec failed：容器内无 jcmd 等
    "not found",           # 命令不存在
    "No such container",   # 容器名不存在（未拉起/已清理）
    "Cannot connect",      # docker daemon 不可达
    "permission denied",   # docker sock 权限不足
    "is not running",      # 容器已退出
)


async def one(tag: str, base_url: str, delay_s: float, timeline: dict,
              start_evt: asyncio.Event) -> None:
    """一路并发请求：记录关键帧相对时刻；req2 收到 AGENT_START 时置 start_evt。"""
    import httpx
    await asyncio.sleep(delay_s)
    t0 = time.monotonic()
    timeline[tag + ":issued"] = time.monotonic()
    if start_evt is not None:
        start_evt.set()  # req2 已发出，开始抓转储
    timeout = httpx.Timeout(connect=10, read=400, write=30, pool=10)
    async with httpx.AsyncClient(timeout=timeout) as c:
        async with c.stream("POST", base_url.rstrip("/") + "/threads/chat",
                            json={"message": MSG, "userId": "eval-test"}) as r:
            if r.status_code // 100 != 2:
                # 非 2xx：该路请求失败。必须 raise（而非 return）——return 会被当成正常样本，
                # 绕过结论区的 req_errors 检查，req1 挂/req2 活时仍打 ✓（取证前提不成立的假绿）
                timeline[tag + f":http_{r.status_code}"] = time.monotonic()
                print(f"[{tag}] HTTP {r.status_code}，本路失败", flush=True)
                raise RuntimeError(f"{tag} HTTP {r.status_code}（非 2xx，取证前提不成立）")
            async for line in r.aiter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if not data or '"type"' not in data:
                    continue
                try:
                    frame = json.loads(data)
                except json.JSONDecodeError:
                    continue
                ftype = frame.get("type")
                if ftype in WATCH_TYPES and (tag + ":" + ftype) not in timeline:
                    timeline[tag + ":" + ftype] = time.monotonic()
                    print(f"[{tag}] {ftype} @ {time.monotonic() - t0:.0f}s", flush=True)
                if ftype == "AGENT_END":
                    return
            # SSE 流结束仍未收到 AGENT_END：服务端提前断流，同属取证前提不成立，
            # 与非 2xx 一样必须 raise 交给 gather 捕获，不得静默返回当正常样本
            raise RuntimeError(f"{tag} SSE 流在 AGENT_END 前结束（服务端提前断流）")


def grab_dump(container: str, idx: int, out_dir: Path) -> Path:
    """容器内 1 号进程线程转储落盘。宿主 docker CLI；非容器环境改 --pid 直抓 jcmd。

    docker/jcmd 失败形态（rc≠0 或 stderr 命中词表）落盘加 (INVALID DUMP: 前缀，不进栈分析。
    词表不扫描成功转储正文，避免正文恰含失败子串被误判（issue #97 假绿修复）。
    """
    dest = out_dir / f"thread-dump-{idx}.txt"
    try:
        out = subprocess.run(
            ["docker", "exec", container, "jcmd", "1", "Thread.print", "-l"],
            capture_output=True, text=True, timeout=30)
        if out.returncode != 0:
            # rc≠0：无论 stdout 是否残留部分输出一律判无效——部分转储不进栈分析，
            # 否则残页恰无阻塞栈会假绿、残页被词表误扫会假失败（失败原因优先取 stderr）
            fail_text = (out.stderr.strip() or out.stdout.strip())[:200]
            text = f"(INVALID DUMP: rc={out.returncode} {fail_text})\n"
        elif any(mark in out.stderr for mark in INVALID_DUMP_MARKS):
            # rc=0 但 stderr 命中失败形态：同样不进栈分析
            text = f"(INVALID DUMP: {out.stderr.strip()[:200]})\n"
        else:
            text = out.stdout
        dest.write_text(text, encoding="utf-8")
    except FileNotFoundError:
        dest.write_text(f"(INVALID DUMP: docker CLI 不可用，跳过第 {idx} 次转储)\n", encoding="utf-8")
    return dest


def is_invalid_dump(path: Path) -> bool:
    """转储是否为无效样本（docker/jcmd 失败形态，grab_dump 落盘时已加 (INVALID DUMP 前缀）。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    return text.startswith("(INVALID DUMP")


def analyze_dump(path: Path) -> bool:
    """转储中是否存在 turn 闸门阻塞栈（缺陷形态证据）。无效转储（docker/jcmd 失败形态）不计。"""
    if is_invalid_dump(path):
        return False
    text = path.read_text(encoding="utf-8", errors="replace")
    parked = any(
        "park" in block or "WAITING" in block
        for block in text.split("\n\n")
    )
    return GATE_STACK_MARK in text and parked


async def main_async(args: argparse.Namespace) -> int:
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    timeline: dict[str, float] = {}
    start_evt = asyncio.Event()
    base = args.base_url

    loop = asyncio.get_event_loop()
    req2 = asyncio.create_task(one("req2", base, args.delay, timeline, start_evt))
    req1 = asyncio.create_task(one("req1", base, 0, timeline, None))

    # req2 发出后周期抓转储，直至其 AGENT_START 出现（转储停止）
    idx = 0
    dumps: list[Path] = []
    started = False
    while not req2.done() and idx < args.max_dumps:
        if not started:
            try:
                await asyncio.wait_for(start_evt.wait(), timeout=1)
                started = True
                start_evt.clear()
            except asyncio.TimeoutError:
                continue
        idx += 1
        p = await loop.run_in_executor(None, grab_dump, args.container, idx, out_dir)
        dumps.append(p)
        print(f"[diag] thread dump #{idx} -> {p}", flush=True)
        await asyncio.sleep(args.interval)

    results = await asyncio.gather(req1, req2, return_exceptions=True)

    print("\n===== 时间线 =====")
    base_t = timeline.get("req1:issued")
    for k in sorted(timeline, key=timeline.get):
        print(f"  {k:<22} +{timeline[k] - base_t:.1f}s")

    print("\n===== 结论 =====")
    # gather(return_exceptions=True) 会吞掉子任务异常：逐个检查，
    # 任一路异常终止即取证前提不成立，不得按剩余样本下结论（issue #97 假绿修复）
    req_errors = [(tag, r) for tag, r in zip(("req1", "req2"), results) if isinstance(r, BaseException)]
    if req_errors:
        for tag, err in req_errors:
            print(f"  ✗ {tag} 异常终止：{err!r}")
        print("  请求未正常完成，取证不成立")
        return 1

    gate_found = any(analyze_dump(p) for p in dumps)
    invalid_dumps = [p for p in dumps if is_invalid_dump(p)]
    valid_count = len(dumps) - len(invalid_dumps)
    # 有效转储数为零（含完全没抓到转储）不得打 ✓：没有证据 ≠ 通过（issue #97 假绿修复）
    if valid_count == 0:
        if not dumps:
            print("  ✗ 无转储（req2 未经历可观察排队窗口或 docker 不可用），取证不成立")
        else:
            reason = invalid_dumps[0].read_text(encoding="utf-8", errors="replace").strip()[:200]
            print(f"  ✗ 有效转储 0/{len(dumps)}，取证不成立，首个无效原因：{reason}")
        return 2
    if gate_found:
        print(f"  ✗ 缺陷形态：转储含 {GATE_STACK_MARK} 阻塞栈（全局 turn 闸门仍在）")
        return 1
    print(f"  ✓ 转储无 {GATE_STACK_MARK} 阻塞栈（每会话闸门形态）")
    r2_start = timeline.get("req2:AGENT_START")
    r2_created = timeline.get("req2:session_created")
    r1_end = timeline.get("req1:AGENT_END")
    # 口径与 bench C 档一致：排队签名 = AGENT_START − session_created 首帧（非绝对时长）
    if r2_start and r2_created:
        delay = r2_start - r2_created
        print(f"  req2 排队签名（AGENT_START − session_created）{delay:.1f}s"
              + (f"（req1 AGENT_END 后 {r2_start - r1_end:.1f}s）" if r1_end else ""))
        if delay > args.start_delay_threshold_ms / 1000:
            print(f"  ✗ 超过阈值 {args.start_delay_threshold_ms}ms：仍存在跨会话排队")
            return 1
        print(f"  ✓ 启动延迟 ≤ {args.start_delay_threshold_ms}ms 阈值")
        return 0
    if r2_start:
        # 有 AGENT_START 却缺 session_created 基线帧 = 零帧缺陷形态（issue #97 问题12），无法证明修复
        print("  ✗ req2 有 AGENT_START 但无 session_created 基线帧（零帧形态），无法计算排队签名")
        return 1
    print("  ✗ req2 未观测到 AGENT_START，无法评估启动延迟")
    return 1


def main() -> int:
    p = argparse.ArgumentParser(description="并发 turn 排队取证（issue #87）")
    p.add_argument("--base-url", default="http://127.0.0.1:18100")
    p.add_argument("--container", default="bench-agent-fw",
                   help="被测容器名（docker exec jcmd）；非容器环境留空并用 --pid")
    p.add_argument("--delay", type=float, default=2.0, help="req2 相对 req1 的发起延迟秒")
    p.add_argument("--interval", type=float, default=2.0, help="转储抓取间隔秒")
    p.add_argument("--max-dumps", type=int, default=10, help="转储上限（防无限抓取）")
    p.add_argument("--start-delay-threshold-ms", type=int, default=10000,
                   help="req2 启动延迟判定阈值；口径与 bench C 档一致：排队签名 = "
                        "AGENT_START − session_created 首帧（非请求发出到 AGENT_START 的绝对时长），"
                        "缺首帧基线（零帧形态）判 FAIL")
    p.add_argument("--out", default="bench/diag/diag-out")
    args = p.parse_args()
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    sys.exit(main())
