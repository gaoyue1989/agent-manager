"""SSE 客户端 + 帧映射解析：POST /threads/chat 采集全量事件帧，构建统一轨迹视图。

帧词表契约来自 config/frame-mapping.json（唯一事实源），本模块不硬编码任何帧名。
"""

import asyncio
import json
import time
from pathlib import Path
from typing import Any

import httpx

_MAPPING_PATH = Path(__file__).resolve().parent.parent / "config" / "frame-mapping.json"
with open(_MAPPING_PATH, encoding="utf-8") as f:
    MAPPING: dict[str, Any] = json.load(f)

_USAGE = MAPPING["usage_frame"]
_HITL = MAPPING["hitl"]
_FINAL = MAPPING["final_output"]


def load_mapping() -> dict[str, Any]:
    """返回帧映射契约（供 checks / selftest 共用，避免各处重复读盘）。"""
    return MAPPING


class SSEError(Exception):
    """SSE 采集失败（非 200 响应、连接中断等传输层错误）。"""

    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


async def stream_chat(
    base_url: str,
    payload: dict[str, Any],
    timeout_s: float = 120.0,
    on_frame: Any = None,
) -> tuple[str | None, list[dict[str, Any]]]:
    """执行一次 SSE 对话，返回 (session_id, 事件列表)。

    事件元素: {"t_ms": 相对起始毫秒, "type": 帧类型, "raw": 原始 payload}。
    on_frame(event_dict) 可选回调（用于 HITL 过程中实时感知 permission_ask）。
    传输层失败抛 SSEError；业务层 error 帧不算传输错误（留给 checks 判定）。
    """
    url = f"{base_url.rstrip('/')}/threads/chat"
    events: list[dict[str, Any]] = []
    session_id: str | None = None
    t0 = time.monotonic()
    timeout = httpx.Timeout(connect=10.0, write=30.0, read=max(timeout_s, 5.0), pool=10.0)
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            async with client.stream("POST", url, json=payload) as resp:
                if resp.status_code != 200:
                    body = (await resp.aread()).decode("utf-8", errors="replace")
                    raise SSEError(
                        f"chat 接口返回 {resp.status_code}: {body[:300]}", status=resp.status_code)
                async for line in resp.aiter_lines():
                    # SSE 规范：只消费 data: 行，忽略空行/注释行/event:/id: 行
                    if not line.startswith("data:"):
                        continue
                    data = line[len("data:"):].strip()
                    if not data:
                        continue
                    try:
                        frame = json.loads(data)
                    except json.JSONDecodeError:
                        continue
                    ftype = frame.get("type")
                    if ftype is None:
                        continue
                    if ftype == "session_created":
                        session_id = frame.get("session_id")
                    event = {"t_ms": int((time.monotonic() - t0) * 1000),
                             "type": ftype, "raw": frame}
                    events.append(event)
                    if on_frame is not None:
                        on_frame(event)
    except httpx.HTTPError as e:
        # 已采集的事件仍然保留（可能是中途断流，对根因分析有价值）
        if events:
            return session_id, events
        raise SSEError(f"chat SSE 采集失败: {e}") from e
    return session_id, events


async def stream_confirm(
    base_url: str, session_id: str, results: list[dict[str, Any]],
    timeout_s: float = 120.0, t_offset_ms: int = 0,
) -> list[dict[str, Any]]:
    """HITL 确认后续段采集：POST /threads/{sid}/confirm-stream，返回续段事件列表。

    results 形如 [{"tool_call_id": ..., "confirmed": true}]（AgentRuntimeService.java:404 契约）。
    t_offset_ms：续段事件时间戳偏移基线（调用方传入主段末事件 t_ms，保证轨迹时间轴单调）。
    """
    url = f"{base_url.rstrip('/')}/threads/{session_id}/confirm-stream"
    events: list[dict[str, Any]] = []
    t0 = time.monotonic()
    timeout = httpx.Timeout(connect=10.0, write=30.0, read=max(timeout_s, 5.0), pool=10.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        async with client.stream("POST", url, json={"results": results}) as resp:
            if resp.status_code != 200:
                body = (await resp.aread()).decode("utf-8", errors="replace")
                raise SSEError(
                    f"confirm-stream 返回 {resp.status_code}: {body[:300]}", status=resp.status_code)
            async for line in resp.aiter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[len("data:"):].strip()
                if not data:
                    continue
                try:
                    frame = json.loads(data)
                except json.JSONDecodeError:
                    continue
                ftype = frame.get("type")
                if ftype is None:
                    continue
                # 续段时间戳与主段衔接：t_offset_ms 为主段末事件时刻
                events.append({"t_ms": int((time.monotonic() - t0) * 1000) + t_offset_ms,
                               "type": ftype, "raw": frame})
    return events


def build_view(events: list[dict[str, Any]]) -> dict[str, Any]:
    """把原始事件序列解析为结构化视图（统一轨迹 schema §4.2 的 views 层）。

    断言与打分只消费本视图，不重复解析原始流。字段：
    frame_counts / tool_calls / deliveries / hitl / token_usage / final_output / error_frames / terminal
    """
    frame_counts: dict[str, int] = {}
    order: list[str] = []
    calls_by_id: dict[str, dict[str, Any]] = {}
    tool_calls: list[dict[str, Any]] = []
    deliveries: list[dict[str, Any]] = []
    hitl_asks: list[dict[str, Any]] = []
    hitl_confirmed = False
    usage = {"input": 0, "output": 0, "total": 0}
    text_parts: list[str] = []
    error_frames: list[dict[str, Any]] = []
    last_seen: dict[str, int] = {}
    terminal: str | None = None

    text_delta_frame = _FINAL["text_delta_frame"]
    text_delta_field = _FINAL["text_delta_field"]
    usage_fields = _USAGE["fields"]

    for ev in events:
        ftype = ev["type"]
        raw = ev.get("raw", {})
        frame_counts[ftype] = frame_counts.get(ftype, 0) + 1
        order.append(ftype)
        last_seen[ftype] = ev["t_ms"]

        if ftype == text_delta_frame:
            delta = raw.get(text_delta_field)
            if isinstance(delta, str):
                text_parts.append(delta)
        elif ftype == "TOOL_CALL_START":
            cid = raw.get("toolCallId")
            if cid and cid not in calls_by_id:
                call = {"name": raw.get("toolName"), "tool_call_id": cid,
                        "t_ms": ev["t_ms"], "result_state": None, "summary": False}
                calls_by_id[cid] = call
                tool_calls.append(call)
        elif ftype == "tool_call_summary":
            # 合成摘要帧：作为对应工具的证据补全（可能先于/独立于 START 到达的紧凑形态）
            name = raw.get("tool_name") or raw.get("toolName")
            if name and not any(c["name"] == name for c in tool_calls):
                tool_calls.append({"name": name, "tool_call_id": raw.get("tool_call_id"),
                                   "t_ms": ev["t_ms"], "result_state": None, "summary": True})
        elif ftype == "TOOL_RESULT_END":
            cid = raw.get("toolCallId")
            if cid and cid in calls_by_id:
                calls_by_id[cid]["result_state"] = raw.get("state")
        elif ftype == _USAGE["type"]:
            for key, field in usage_fields.items():
                val = raw.get(field)
                if isinstance(val, (int, float)):
                    usage[key] += int(val)
        elif ftype == _HITL["ask_frame"]:
            hitl_asks.append({
                "t_ms": ev["t_ms"],
                "tools": [c.get("name") for c in raw.get(_HITL["ask_tool_calls_field"], [])],
                "tool_calls": list(raw.get(_HITL["ask_tool_calls_field"], [])),
                "reply_id": raw.get("reply_id"),
            })
        elif ftype == _HITL["result_frame"]:
            hitl_confirmed = True
        elif ftype == "file_ready":
            deliveries.append({"t_ms": ev["t_ms"], "file_id": raw.get("file_id"),
                               "file_name": raw.get("file_name"), "url": raw.get("url")})
        elif ftype == "error":
            error_frames.append({"t_ms": ev["t_ms"], "error": raw.get("error") or raw.get("message")})

    # 终止帧 = terminal_frames 中时间上最后出现的（HITL 场景 done 之后还可能来 permission_ask）
    present = [t for t in MAPPING["terminal_frames"] if t in frame_counts]
    if present:
        terminal = max(present, key=lambda t: last_seen[t])
    return {
        "frame_counts": frame_counts,
        "frame_order": order,
        "tool_calls": tool_calls,
        "deliveries": deliveries,
        "hitl": {"asks": hitl_asks, "confirmed": hitl_confirmed},
        "token_usage": usage,
        "final_output": "".join(text_parts),
        "error_frames": error_frames,
        "terminal": terminal,
    }


async def delete_session(base_url: str, session_id: str) -> bool:
    """删除单个会话。200 且 deleted=true、或 404（已不存在）视为成功，其余失败。"""
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(10.0)) as client:
            resp = await client.delete(f"{base_url.rstrip('/')}/threads/{session_id}")
        if resp.status_code == 404:
            return True
        if resp.status_code == 200:
            return bool(resp.json().get("deleted", True))
        return False
    except (httpx.HTTPError, ValueError):
        # ValueError：DELETE 200 但响应体非 JSON（按失败处理，让上层重试）
        return False


async def cleanup_sessions(base_url: str, session_ids: set[str]) -> tuple[int, list[str]]:
    """尽力删除评测会话；失败（如 turn 租约未释放的 409）等 3 秒重试一次。

    返回 (成功数, 仍失败 sid 列表)——调用方必须对仍失败项显式告警，不得静默。
    """
    deleted, failed = 0, []
    for sid in session_ids:
        if await delete_session(base_url, sid):
            deleted += 1
        else:
            failed.append(sid)
    if failed:
        await asyncio.sleep(3)
        still = []
        for sid in failed:
            if await delete_session(base_url, sid):
                deleted += 1
            else:
                still.append(sid)
        return deleted, still
    return deleted, []
