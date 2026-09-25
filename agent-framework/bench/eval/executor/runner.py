"""用例执行编排：能力匹配跳过、单次执行（含 HITL 自动确认）、套件并发调度。"""

import asyncio
import json
import time
from pathlib import Path
from typing import Any

from . import checks as checks_mod
from . import sse_client

_REQUIRED_CASE_KEYS = ("case_id", "title", "category", "input", "expected")

# 框架内置 @Tool 工具（echo/get_current_time/present_file/present_url）运行时无条件注册，
# /tools?includeInternal=true 只反映 OAF 声明视图——2026-09-25 对 release-agent 实测：
# 未声明 echo 时 echo 用例仍 PASS。故内置工具视为恒可用，能力门禁只管 MCP 工具。
_BUILTIN_TOOLS = frozenset({"echo", "get_current_time", "present_file", "present_url"})


def load_cases(cases_dir: Path) -> list[dict[str, Any]]:
    """加载用例库（bench/eval/cases/*.json），校验必填字段，retired 用例剔除。"""
    cases: list[dict[str, Any]] = []
    for path in sorted(cases_dir.glob("*.json")):
        with open(path, encoding="utf-8") as f:
            case = json.load(f)
        missing = [k for k in _REQUIRED_CASE_KEYS if k not in case]
        if missing:
            raise ValueError(f"用例 {path.name} 缺少必填字段: {missing}")
        if case.get("status") == "retired":
            continue
        case["_file"] = path.name
        cases.append(case)
    return cases


async def fetch_capabilities(base_url: str) -> set[str]:
    """获取被测 Agent 可用工具名集合（MCP + 内置），用于用例能力匹配。"""
    async with httpx_client() as client:
        resp = await client.get(f"{base_url.rstrip('/')}/tools",
                                params={"includeInternal": "true"})
        resp.raise_for_status()
        tools = resp.json().get("tools", [])
        return {t.get("name") for t in tools if t.get("name")}


def httpx_client() -> Any:
    import httpx
    return httpx.AsyncClient(timeout=httpx.Timeout(15.0))


async def execute_once(
    case: dict[str, Any], base_url: str, repeat_no: int = 1,
) -> dict[str, Any]:
    """执行一次用例，返回统一轨迹（schema §4.2）：原始事件 + 结构化视图 + 判定结果。

    status: success（收到终止帧）| failed（传输/HTTP 错误）| timeout（整体超时）。
    HITL：流以 permission_ask 终止且 hitl_policy=auto_confirm 时，调 confirm-stream
    续段并把续段事件并入同一轨迹。
    """
    inp = case["input"]
    payload = {"message": inp.get("message", ""), "userId": inp.get("userId", "eval-test")}
    if inp.get("sessionId"):
        payload["sessionId"] = inp["sessionId"]
    if inp.get("model"):
        # 会话级模型切换（POST /threads/chat 请求体 model 字段，25246e3）：直测 API 语义
        payload["model"] = inp["model"]
    timeout_s = float(case.get("timeout_s", 120))
    policy = case.get("hitl_policy", "none")

    t0 = time.monotonic()
    events: list[dict[str, Any]] = []
    session_id: str | None = None
    status = "failed"
    error_info: str | None = None

    try:
        got_sid, main_events = await asyncio.wait_for(
            sse_client.stream_chat(base_url, payload, timeout_s),
            timeout=timeout_s + 5,
        )
        session_id = got_sid
        events = list(main_events)
        view_probe = sse_client.build_view(events)
        pending_ask = view_probe["terminal"] == sse_client.MAPPING["hitl"]["ask_frame"]

        if pending_ask and policy == "auto_confirm" and session_id:
            # 自动确认：按 permission_ask 帧携带的 tool_calls 明细逐个确认
            results = [{"tool_call_id": tc.get("tool_call_id"), "confirmed": True}
                       for ask in view_probe["hitl"]["asks"]
                       for tc in ask.get("tool_calls", [])]
            more = await sse_client.stream_confirm(
                base_url, session_id, results, timeout_s,
                t_offset_ms=events[-1]["t_ms"] if events else 0)
            events.extend(more)
    except asyncio.TimeoutError:
        status = "timeout"
        error_info = f"整体超时（>{timeout_s}s）"
    except sse_client.SSEError as e:
        status = "failed"
        error_info = str(e)
    except Exception as e:  # 防御：单用例异常不拖垮整个套件
        status = "failed"
        error_info = f"{type(e).__name__}: {e}"

    duration_ms = int((time.monotonic() - t0) * 1000)
    view = sse_client.build_view(events)
    if status == "failed" and view["terminal"] is not None:
        # 流虽报传输异常但已收到终止帧：按成功采集处理（断流细节记录在 error_info）
        status = "success"

    check_results = checks_mod.evaluate(case.get("expected", {}), view)
    return {
        "case_id": case["case_id"],
        "repeat_no": repeat_no,
        "session_id": session_id,
        "status": status,
        "duration_ms": duration_ms,
        "events": events,
        "view": view,
        "check_results": check_results,
        "checks_passed": all(c["passed"] for c in check_results) if check_results else None,
        "error_info": error_info,
        "hitl_confirmed": view["hitl"]["confirmed"],
    }


async def run_suite(
    cases: list[dict[str, Any]], base_url: str,
    repeat: int = 3, workers: int = 5, capabilities: set[str] | None = None,
    on_trace: Any = None,
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    """并发执行用例套件（每用例 repeat 次），返回 (traces, skipped)。

    能力匹配：requires_tools 中存在被测 Agent 不可用的工具 → 整条用例 skip。
    on_trace(trace)：每条轨迹完成即回调（长跑任务的增量进度，避免整体静默）。
    """
    skipped: list[dict[str, str]] = []
    runnable: list[dict[str, Any]] = []
    if capabilities is not None:
        effective = set(capabilities) | _BUILTIN_TOOLS
        for case in cases:
            missing = [t for t in case.get("requires_tools") or [] if t not in effective]
            if missing:
                skipped.append({"case_id": case["case_id"],
                                "reason": f"被测 Agent 缺少工具: {missing}"})
            else:
                runnable.append(case)
    else:
        runnable = list(cases)

    sem = asyncio.Semaphore(max(1, workers))

    async def one(case: dict[str, Any], no: int) -> dict[str, Any]:
        async with sem:
            trace = await execute_once(case, base_url, repeat_no=no)
            if on_trace is not None:
                on_trace(trace)
            return trace

    tasks = [one(case, no) for case in runnable for no in range(1, repeat + 1)]
    traces = list(await asyncio.gather(*tasks))
    return traces, skipped


async def cleanup_sessions(base_url: str, traces: list[dict[str, Any]]) -> tuple[int, list[str]]:
    """删除本轮评测创建的会话（共享被测实例时保持环境干净）。

    返回 (成功数, 仍失败 sid 列表)；仍失败项由调用方显式告警。
    """
    sids = {t["session_id"] for t in traces if t.get("session_id")}
    return await sse_client.cleanup_sessions(base_url, sids)
