"""deepeval 导出器：标准条目 → EvaluationDataset 兼容 JSONL（纯文件，零新依赖）。

格式依据 deepeval 官方文档（evaluation-datasets / golden-synthesizer）：
  单轮 golden：{"input", "expected_output", "context"?, "expected_tools"?,
               "additional_metadata"?, "comments"?}
  多轮 conversational golden：{"scenario", "expected_outcome"?, "context"?, ...}
  —— 深度约定：单/多轮严禁混排同一文件（deepeval 加载按 scenario 键判别会报错），
  故分文件输出 goldens.jsonl 与 conversational_goldens.jsonl。
  testcases 视图（LLMTestCase）：{"input", "actual_output", "expected_output"?,
  "tools_called"?, ...}——从 run 轨迹导出，供「已有输出直接离线评测」。

turns 不导出：conversational golden 的对话轮留给 deepeval 评测期模拟生成
（官方推荐用法），录制轮次经 scenario/expected_outcome 概括，不逐轮伪造 assistant 内容。
"""

import json
from pathlib import Path
from typing import Any

_GENERATOR = "agent-manager-eval/exporters"


def _meta_of(item: dict[str, Any]) -> dict[str, Any]:
    """additional_metadata：溯源字段白名单透传（深度差异字段一律不进外部格式）。"""
    m = item.get("metadata") or {}
    out: dict[str, Any] = {"generator": _GENERATOR}
    for k in ("case_id", "category", "priority", "module", "source", "eval_dimensions"):
        if m.get(k) is not None:
            out[k] = m[k]
    return out


def _comments_of(item: dict[str, Any]) -> str | None:
    title = (item.get("metadata") or {}).get("title")
    return title if title else None


def _tool_calls(names: list[str] | None) -> list[dict[str, Any]] | None:
    """deepeval 的 expected_tools/tools_called 是 ToolCall 映射列表（实测字符串列表解析崩），
    最小合法形态 {"name": ...}（description/arguments 可选）。"""
    if not names:
        return None
    return [{"name": n} for n in names]


def _dump(line: dict[str, Any]) -> dict[str, Any]:
    """省略 None 值键（不造 null 字段，保持导出文件即文档）。"""
    return {k: v for k, v in line.items() if v is not None}


def render_goldens(items: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """拆分单轮/多轮两条流。多轮判定：turns 非空（evalpack 会话）。"""
    single: list[dict[str, Any]] = []
    conv: list[dict[str, Any]] = []
    for it in items:
        meta = _meta_of(it)
        comments = _comments_of(it)
        if it.get("turns"):
            conv.append(_dump({
                "scenario": it["input_message"],
                "expected_outcome": it.get("ground_truth"),
                "expected_tools": _tool_calls(it.get("expected_tools")),
                "additional_metadata": meta,
                "comments": comments,
            }))
        else:
            single.append(_dump({
                "input": it["input_message"],
                "expected_output": it.get("ground_truth"),
                "expected_tools": _tool_calls(it.get("expected_tools")),
                "additional_metadata": meta,
                "comments": comments,
            }))
    return single, conv


def render_testcases(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """LLMTestCase 行：actual_output 缺失的条目剔除（deepeval 加载时必填）。"""
    out: list[dict[str, Any]] = []
    for it in items:
        if not it.get("actual_output"):
            continue
        out.append(_dump({
            "input": it["input_message"],
            "actual_output": it["actual_output"],
            "expected_output": it.get("ground_truth"),
            "tools_called": _tool_calls(it.get("final_tools_called")),
            "expected_tools": _tool_calls(it.get("expected_tools")),
            "additional_metadata": _meta_of(it),
            "comments": _comments_of(it),
        }))
    return out


def write_jsonl(path: str | Path, lines: list[dict[str, Any]]) -> int:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for line in lines:
            f.write(json.dumps(line, ensure_ascii=False) + "\n")
    return len(lines)


def export(items: list[dict[str, Any]], out_dir: str | Path,
           kind: str = "goldens") -> list[Path]:
    """写出 deepeval JSONL 文件，返回产物路径清单（kind: goldens | testcases）。"""
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    produced: list[Path] = []
    if kind == "testcases":
        lines = render_testcases(items)
        path = out / "testcases.jsonl"
        write_jsonl(path, lines)
        produced.append(path)
        return produced
    single, conv = render_goldens(items)
    if single:
        path = out / "goldens.jsonl"
        write_jsonl(path, single)
        produced.append(path)
    if conv:
        path = out / "conversational_goldens.jsonl"
        write_jsonl(path, conv)
        produced.append(path)
    return produced
