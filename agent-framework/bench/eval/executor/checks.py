"""确定性断言检查器：用例 expected → 轨迹视图判定（门禁轨/趋势轨共用，零 LLM 依赖）。

expected 支持的机器可判键（全部可选，缺省不检查）：
  frames              {帧类型: 数量}，数量为 int（精确）或 ">=n"/"<=n" 字符串
  frame_order         [[A, B], ...]  A 首次出现必须早于 B 首次出现
  tool_calls          {"required": [名...], "forbidden": [名...]}
  tool_result         {工具名: {"ok": true/false}}，ok=true 即 state==SUCCESS
  final_text_contains [子串...]，final_output 必须包含
  final_text_min_len  int，final_output 最小长度
  error_contains      [子串...]，error 帧文本必须包含（用于负向用例断言报错内容）
  no_error            true 时不允许出现 error 帧
"""

from typing import Any

_ERROR_LIKE = {"ERROR", "INTERRUPTED", "DENIED"}


def _count_match(actual: int, expected: Any) -> bool:
    if isinstance(expected, bool):
        return actual == int(expected)
    if isinstance(expected, int):
        return actual == expected
    if isinstance(expected, str):
        s = expected.strip()
        if s.startswith(">="):
            return actual >= int(s[2:])
        if s.startswith("<="):
            return actual <= int(s[2:])
        if s.startswith(">"):
            return actual > int(s[1:])
    return False


def _frame_names(view: dict[str, Any]) -> set[str]:
    return set(view["frame_counts"])


def evaluate(expected: dict[str, Any], view: dict[str, Any]) -> list[dict[str, Any]]:
    """评估全部声明了的检查项，返回 [{name, passed, detail}]（按 expected 键顺序）。"""
    results: list[dict[str, Any]] = []
    counts = view["frame_counts"]
    tool_names = [c.get("name") for c in view["tool_calls"]]

    def add(name: str, passed: bool, detail: str) -> None:
        results.append({"name": name, "passed": passed, "detail": detail})

    for ftype, want in (expected.get("frames") or {}).items():
        actual = counts.get(ftype, 0)
        add(f"frames.{ftype}", _count_match(actual, want),
            f"期望 {want}，实际 {actual}")

    for pair in expected.get("frame_order") or []:
        a, b = pair[0], pair[1]
        order = view["frame_order"]
        ia = order.index(a) if a in order else -1
        ib = order.index(b) if b in order else -1
        ok = ia != -1 and ib != -1 and ia < ib
        add(f"frame_order.{a}<{b}", ok,
            f"{a}@{ia} → {b}@{ib}" + ("（帧缺失）" if ia == -1 or ib == -1 else ""))

    tc = expected.get("tool_calls") or {}
    for name in tc.get("required") or []:
        add(f"tool_calls.required[{name}]", name in tool_names,
            f"工具 {'已' if name in tool_names else '未'}调用")
    for name in tc.get("forbidden") or []:
        add(f"tool_calls.forbidden[{name}]", name not in tool_names,
            f"调用次数 {tool_names.count(name)}")

    for name, rule in (expected.get("tool_result") or {}).items():
        call = next((c for c in view["tool_calls"] if c.get("name") == name), None)
        if call is None:
            add(f"tool_result[{name}].ok", False, "工具未被调用，无结果状态")
            continue
        state = call.get("result_state")
        if state is None:
            add(f"tool_result[{name}].ok", False, "未收到 TOOL_RESULT_END，结果状态未知")
            continue
        want_ok = bool(rule.get("ok", True))
        is_ok = state not in _ERROR_LIKE if want_ok else state in _ERROR_LIKE
        add(f"tool_result[{name}].ok", is_ok, f"state={state}")

    final_text = view["final_output"] or ""
    for sub in expected.get("final_text_contains") or []:
        add(f"final_text_contains[{sub[:20]}]", sub in final_text,
            f"final_output 长度 {len(final_text)}")
    min_len = expected.get("final_text_min_len")
    if min_len is not None:
        add("final_text_min_len", len(final_text) >= int(min_len),
            f"期望 ≥{min_len}，实际 {len(final_text)}")

    err_texts = [e.get("error") or "" for e in view["error_frames"]]
    for sub in expected.get("error_contains") or []:
        add(f"error_contains[{sub[:20]}]", any(sub in t for t in err_texts),
            f"error 帧 {len(err_texts)} 个")
    if expected.get("no_error"):
        add("no_error", not err_texts,
            "无 error 帧" if not err_texts else f"error 帧: {err_texts[0]}")

    return results
