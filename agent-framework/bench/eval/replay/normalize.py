"""归一化规则表：轨迹确定性的基础（设计 §4.3）。

时间戳/UUID/长随机 token/主机端口等 volatile 内容遮蔽后，才能对录制请求与回放请求做
结构等价比对（否则每次调用的 id/时间都会造成假漂移）。规则同时被 packager（会话关联
指纹）与 replay-llm（请求匹配）消费；Node 侧 replay-llm 内置同构 JS 版本，两侧改动
必须同步（selftest 钉住）。
"""

import hashlib
import json
import re
from typing import Any

# 归一化规则表（顺序敏感：先长模式后短模式）
RULES: list[tuple[str, str]] = [
    # ISO-8601 时间戳（含毫秒/时区变体）
    (r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?", "<TS>"),
    # epoch 毫秒（13 位数字，避免误伤普通数字：前后不能是数字）
    (r"(?<![\d.])1[3-9]\d{11}(?![\d])", "<TS_MS>"),
    # UUID（含 nanoid 形态 21 位 base62 简化覆盖）
    (r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<UUID>"),
    # 框架 gw-hash 用户身份（AgentStateStore ID 等，12 位左右 hex；协议面 peer/gw-hash 规范化产物）
    (r"\bgw-[0-9a-fA-F]{6,}", "<GW>"),
    # 主机:端口（回放环境地址差异）
    (r"(?:https?://)?(?:127\.0\.0\.1|localhost)(?::\d+)?", "<HOST>"),
    # 长 hex / base64url token（≥16 位，前后非字母数字）
    (r"(?<![A-Za-z0-9_-])[A-Fa-f0-9]{16,}(?![A-Za-z0-9_-])", "<TOKEN>"),
    (r"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{22}(?![A-Za-z0-9_-])", "<TOKEN>"),
]

_COMPILED = [(re.compile(p), r) for p, r in RULES]


def normalize_text(s: str) -> str:
    """对文本应用全部遮蔽规则。"""
    for rex, repl in _COMPILED:
        s = rex.sub(repl, s)
    return s


def normalize_obj(v: Any) -> Any:
    """递归归一化：只处理字符串叶子值（数字/布尔保持原样）。"""
    if isinstance(v, str):
        return normalize_text(v)
    if isinstance(v, list):
        return [normalize_obj(x) for x in v]
    if isinstance(v, dict):
        return {k: normalize_obj(x) for k, x in v.items()}
    return v


def normalize_llm_request(req: dict[str, Any]) -> dict[str, Any]:
    """LLM 请求的结构归一形态：model / messages(role+归一化 content) / tools 名单。

    stream/temperature 等采样参数不参与匹配（录制与回放可能合法不同）。
    """
    messages = []
    for m in req.get("messages") or []:
        content = m.get("content")
        if not isinstance(content, str):
            content = json.dumps(content, ensure_ascii=False)
        messages.append({"role": m.get("role"), "content": normalize_text(content)})
    tools = sorted(
        (t.get("function", {}).get("name") or t.get("name") or "")
        for t in req.get("tools") or []
    )
    out: dict[str, Any] = {"messages": messages}
    if req.get("model"):
        out["model"] = req["model"]
    if tools:
        out["tools"] = tools
    return out


def request_fingerprint(req: dict[str, Any]) -> str:
    """归一化请求的稳定指纹（会话关联与回放匹配共用）。"""
    blob = json.dumps(normalize_llm_request(req), ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def first_user_text(req: dict[str, Any]) -> str:
    """请求中第一条 user 消息的归一化文本（会话分组的锚点）。"""
    for m in req.get("messages") or []:
        if m.get("role") == "user":
            c = m.get("content")
            return normalize_text(c if isinstance(c, str) else json.dumps(c, ensure_ascii=False))
    return ""
