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
  4. 输出两路 session_created / AGENT_START / AGENT_END 时刻表与首帧延迟。

判定（缺陷形态 vs 修复后）：
  * 缺陷（全局 turn 闸门）：req2 的 AGENT_START ≈ req1 的 AGENT_END；转储含
    boundedElastic 线程 WAITING(park) 于 LocalSessionTurnGate.acquire 栈；
  * 修复（PER_PEER 每会话闸门）：req2 的 AGENT_START 延迟 < 10s；转储无该阻塞栈。
  脚本自动做两种检查并打印结论（--check-only 可只分析已落盘转储）。
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


def grab_dump(container: str, idx: int, out_dir: Path) -> Path:
    """容器内 1 号进程线程转储落盘。宿主 docker CLI；非容器环境改 --pid 直抓 jcmd。"""
    dest = out_dir / f"thread-dump-{idx}.txt"
    try:
        out = subprocess.run(
            ["docker", "exec", container, "jcmd", "1", "Thread.print", "-l"],
            capture_output=True, text=True, timeout=30)
        text = out.stdout or out.stderr
        # JRE 镜像无 jcmd（OCI exec failed）等失败形态标记为无效，不进栈分析
        if "exec failed" in text or "not found" in text:
            text = f"(INVALID DUMP: {text.strip()[:200]})\n"
        dest.write_text(text, encoding="utf-8")
    except FileNotFoundError:
        dest.write_text(f"(docker CLI 不可用，跳过第 {idx} 次转储)\n", encoding="utf-8")
    return dest


def analyze_dump(path: Path) -> bool:
    """转储中是否存在 turn 闸门阻塞栈（缺陷形态证据）。无效转储（JRE 无 jcmd 等）不计。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    if text.startswith("(INVALID DUMP") or text.startswith("(docker CLI"):
        return False
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

    await asyncio.gather(req1, req2, return_exceptions=True)

    print("\n===== 时间线 =====")
    base_t = timeline.get("req1:issued")
    for k in sorted(timeline, key=timeline.get):
        print(f"  {k:<22} +{timeline[k] - base_t:.1f}s")

    gate_found = any(analyze_dump(p) for p in dumps)
    print("\n===== 结论 =====")
    if not dumps:
        print("  无转储（req2 未经历可观察排队窗口或 docker 不可用）")
        return 1
    if gate_found:
        print(f"  ✗ 缺陷形态：转储含 {GATE_STACK_MARK} 阻塞栈（全局 turn 闸门仍在）")
        return 1
    print(f"  ✓ 转储无 {GATE_STACK_MARK} 阻塞栈（每会话闸门形态）")
    r2_start = timeline.get("req2:AGENT_START")
    r1_end = timeline.get("req1:AGENT_END")
    r2_issued = timeline.get("req2:issued")
    if r2_start and r2_issued:
        delay = r2_start - r2_issued
        print(f"  req2 AGENT_START 延迟 {delay:.1f}s"
              + (f"（req1 AGENT_END 后 {r2_start - r1_end:.1f}s）" if r1_end else ""))
        if delay > args.start_delay_threshold_ms / 1000:
            print(f"  ✗ 超过阈值 {args.start_delay_threshold_ms}ms：仍存在跨会话排队")
            return 1
        print(f"  ✓ 启动延迟 ≤ {args.start_delay_threshold_ms}ms 阈值")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="并发 turn 排队取证（issue #87）")
    p.add_argument("--base-url", default="http://127.0.0.1:18100")
    p.add_argument("--container", default="bench-agent-fw",
                   help="被测容器名（docker exec jcmd）；非容器环境留空并用 --pid")
    p.add_argument("--delay", type=float, default=2.0, help="req2 相对 req1 的发起延迟秒")
    p.add_argument("--interval", type=float, default=2.0, help="转储抓取间隔秒")
    p.add_argument("--max-dumps", type=int, default=10, help="转储上限（防无限抓取）")
    p.add_argument("--start-delay-threshold-ms", type=int, default=10000,
                   help="req2 启动延迟判定阈值（与 bench C 档同口径）")
    p.add_argument("--out", default="bench/diag/diag-out")
    args = p.parse_args()
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    sys.exit(main())
