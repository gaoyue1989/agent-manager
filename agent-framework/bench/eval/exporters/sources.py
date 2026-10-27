"""标准条目（item）加载：cases / evalpack / run 产物 → 统一导出中间形态。

item schema（PR1 最小集；PR2 DatasetHub 落地后由 datasets/ 提供同一形态）：
  {
    "id": str,                  # 确定性 id（case_id 或 case_replay_{sid}），路径安全（过 validate_id）
    "input_message": str,       # 单轮输入（多轮取首轮）
    "turns": list | None,       # 多轮用户输入 [{"input": ...}]（仅 evalpack 会话有）
    "ground_truth": str | None, # 语义金标（cases.ground_truth / 会话录制终答）
    "expected_tools": list | None,   # 期望工具（cases.requires_tools / 会话实际工具）
    "final_output": str | None, # 已有输出（录制终答 / run 轨迹终答，testcases 视图用）
    "actual_output": str | None,# run 轨迹终答（仅 load_from_run 填充）
    "metadata": dict,           # 只放 JSON 安全的溯源字段（case_id/category/priority/...）
  }

不脑补原则：外部格式缺对应概念的字段一律省略（不造 null/占位文本）；
langfuse/deepeval 的 id 确定性由调用方按来源前缀保证（见 langfuse_client.build_item）。
"""

import json
from pathlib import Path
from typing import Any

from executor.runner import load_cases as _load_library_cases
from replay.ids import validate_id

# cases 用例透传进 metadata 的溯源字段（全部可选）
_CASE_META_KEYS = ("title", "category", "priority", "module", "source",
                   "eval_dimensions", "timeout_s", "hitl_policy")


def _base_meta(case_id: str, **extra: Any) -> dict[str, Any]:
    meta: dict[str, Any] = {"case_id": case_id}
    for k, v in extra.items():
        if v is not None:
            meta[k] = v
    return meta


def load_from_cases(cases_dir: str | Path) -> list[dict[str, Any]]:
    """库内用例 → 单轮标准条目（复用 runner.load_cases 的必填校验与 retired 剔除）。"""
    items: list[dict[str, Any]] = []
    for case in _load_library_cases(Path(cases_dir)):
        cid = case["case_id"]
        validate_id("export item id", cid)
        meta = {k: case[k] for k in _CASE_META_KEYS if case.get(k) is not None}
        items.append({
            "id": cid,
            "input_message": case["input"].get("message", ""),
            "turns": None,
            "ground_truth": case.get("ground_truth"),
            # 空列表归一为缺省：requires_tools: [] 语义是「无工具要求」，
            # 导出为 expected_tools: [] 会被 deepeval ToolCorrectness 误读为「禁止调用工具」
            "expected_tools": case.get("requires_tools") or None,
            "final_output": None,
            "actual_output": None,
            "metadata": _base_meta(cid, source_kind="cases", **meta),
        })
    return items


def load_from_pack(pack_dir: str | Path) -> list[dict[str, Any]]:
    """evalpack 会话 → 多轮标准条目（语义金标 = 录制终答，属录制行为的诚实呈现）。"""
    root = Path(pack_dir)
    with open(root / "manifest.json", encoding="utf-8") as f:
        manifest = json.load(f)
    pack_id = manifest.get("pack_id") or root.name
    items: list[dict[str, Any]] = []
    for path in sorted((root / "sessions").glob("*.json")):
        with open(path, encoding="utf-8") as f:
            session = json.load(f)
        sid = session["sid"]
        validate_id("export item id", sid)
        cid = f"case_replay_{sid[:24]}"  # 与 packager cases-draft 的 case_id 同规则
        first_input = (session.get("turns") or [{}])[0].get("input", "")
        if not first_input:
            continue  # 无用户输入的骨架（纯背景调用会话）对外部工具无评测价值，跳过
        items.append({
            "id": cid,
            "input_message": first_input,
            "turns": session.get("turns"),
            "ground_truth": session.get("recorded_final") or None,
            "expected_tools": session.get("tool_names") or None,
            "final_output": session.get("final_output") or None,
            "actual_output": None,
            "metadata": _base_meta(
                cid, source_kind="pack", pack=pack_id, sid=sid,
                confidence=session.get("confidence"),
                category="replay", token_usage=session.get("token_usage")),
        })
    return items


def load_from_run(run_dir: str | Path, cases_dir: str | Path) -> list[dict[str, Any]]:
    """flywheel run 产物轨迹 + 库内用例 → testcases 视图条目（input 来自用例库按 case_id 对齐）。"""
    cases = {c["id"]: c for c in load_from_cases(cases_dir)}
    items: list[dict[str, Any]] = []
    seen: set[str] = set()
    for path in sorted((Path(run_dir) / "traces").glob("*.json")):
        with open(path, encoding="utf-8") as f:
            trace = json.load(f)
        cid = trace.get("case_id")
        if not cid or cid in seen or cid not in cases:
            continue  # 重复轮次取首个；对不上库内用例的轨迹（如 gen 产物）跳过
        seen.add(cid)
        base = cases[cid]
        view = trace.get("view") or {}
        items.append({**base,
                      "actual_output": view.get("final_output") or None,
                      "final_tools_called": [c["name"] for c in view.get("tool_calls") or []
                                             if c.get("name")] or None,
                      "expected_tools": base.get("expected_tools"),
                      "metadata": {**base["metadata"],
                                   "run_status": trace.get("status"),
                                   "checks_passed": trace.get("checks_passed")}})
    return items
