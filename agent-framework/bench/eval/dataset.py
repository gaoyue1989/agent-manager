"""DatasetHub：评测数据集层——唯一事实源（设计 docs/design/agent-framework-eval-dataset-hub-design.md）。

落盘契约（format_version 1）：
  datasets/{service}/{dataset_name}/
  ├── dataset.json          # 元信息 + changelog + stats
  ├── items/{item_id}.json  # 一条一文件（id 即文件名，validate_id 校验）
  └── CHECKSUMS             # 全目录 sha256 清单（复用 replay.packager.verify_checksums 三重校验）

item schema = AgentScope Task 骨架（id/input/ground_truth/metadata）+ 四块扩展：
  turns（多轮用户输入）、expected（内部契约断言，喂 flywheel 检查器）、
  status（人审状态机 draft→reviewed→active→archived）、metadata.source 溯源
  （manual / t1_recorded / t2_skeleton / g1_diff / g2_derived / g3_synth）。

收编统一（PR2 决议）：本层是唯一事实源，bench/eval/cases/ 降为兼容投影
（export --target cases-legacy 再生）；内部执行链路经 to_case() 投影为
runner.load_cases 同构的用例字典，executor/checks 零改动。
"""

import hashlib
import json
import time
from pathlib import Path
from typing import Any

from replay.ids import InvalidIdentifierError, validate_id

FORMAT_VERSION = 1
DATASETS_DIR = Path(__file__).resolve().parent / "datasets"
DEFAULT_SERVICE = "agent-framework"

# 状态机（人审硬闸门：draft/reviewed 不可被执行链路消费，只有 active 可）
STATUS_ORDER = ("draft", "reviewed", "active", "archived")

_ITEM_REQUIRED = ("id", "input", "status")
# 投影回 legacy 用例字典时透传的字段（与 runner._REQUIRED_CASE_KEYS 对齐）
_CASE_META_KEYS = ("title", "category", "priority", "module", "source",
                   "eval_dimensions", "hitl_policy", "timeout_s")


class DatasetError(Exception):
    """数据集层操作错误（结构/状态/路径非法）。"""


def _validated(kind: str, value: str) -> str:
    """id 校验统一转 DatasetError（调用方只捕一种异常）。"""
    try:
        return validate_id(kind, value)
    except InvalidIdentifierError as e:
        raise DatasetError(str(e)) from e


# ---------------------------------------------------------------------------
# 读写
# ---------------------------------------------------------------------------

def dataset_dir(service: str, name: str, datasets_dir: Path | str | None = None) -> Path:
    base = Path(datasets_dir) if datasets_dir else DATASETS_DIR
    _validated("dataset service", service)
    _validated("dataset name", name)
    return base / service / name


def _write_dataset_meta(ddir: Path, service: str, name: str,
                        description: str = "", changelog_note: str = "") -> None:
    meta_path = ddir / "dataset.json"
    meta: dict[str, Any] = {}
    if meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    meta.update({
        "format_version": FORMAT_VERSION,
        "name": name,
        "service": service,
        "description": description or meta.get("description", ""),
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    })
    meta.setdefault("created_at", meta["updated_at"])
    if changelog_note:
        meta.setdefault("changelog", []).append(
            {"ts": meta["updated_at"], "note": changelog_note})
    stats: dict[str, int] = {}
    for it in _iter_items(ddir):
        stats[it["status"]] = stats.get(it["status"], 0) + 1
    meta["stats"] = stats
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)


def _iter_items(ddir: Path) -> list[dict[str, Any]]:
    items_dir = ddir / "items"
    if not items_dir.exists():
        return []
    out = []
    for p in sorted(items_dir.glob("*.json")):
        out.append(json.loads(p.read_text(encoding="utf-8")))
    return out


def write_checksums(ddir: Path) -> None:
    """全目录 sha256 清单（格式与 evalpack CHECKSUMS 一致，校验复用 packager.verify_checksums）。"""
    from replay.packager import verify_checksums  # 局部导入避免环
    lines = []
    for p in sorted(ddir.rglob("*")):
        if p.is_file() and p.name != "CHECKSUMS":
            lines.append(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(ddir)}")
    (ddir / "CHECKSUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if not verify_checksums(ddir):
        raise DatasetError(f"自产 CHECKSUMS 未通过自家校验: {ddir}")


def upsert_item(service: str, name: str, item: dict[str, Any],
                datasets_dir: Path | str | None = None,
                changelog_note: str = "") -> Path:
    """写入/更新一条 item 并刷新 dataset.json 统计与 CHECKSUMS。

    校验必填字段与状态机合法值；id 非法即拒（路径穿越防线）。
    """
    for k in _ITEM_REQUIRED:
        if k not in item:
            raise DatasetError(f"item 缺少必填字段 {k}: {item.get('id', '?')}")
    if item["status"] not in STATUS_ORDER:
        raise DatasetError(f"非法 status {item['status']!r}（合法：{STATUS_ORDER}）")
    iid = item["id"]
    _validated("dataset item id", iid)
    ddir = dataset_dir(service, name, datasets_dir)
    (ddir / "items").mkdir(parents=True, exist_ok=True)
    with open(ddir / "items" / f"{iid}.json", "w", encoding="utf-8") as f:
        json.dump(item, f, ensure_ascii=False, indent=1)
    _write_dataset_meta(ddir, service, name, changelog_note=changelog_note)
    write_checksums(ddir)
    return ddir / "items" / f"{iid}.json"


def set_status(service: str, name: str, item_id: str, status: str,
               datasets_dir: Path | str | None = None) -> dict[str, Any]:
    """状态机流转：仅允许沿 STATUS_ORDER 单向前进（archive 可从任意态进入）。"""
    if status not in STATUS_ORDER:
        raise DatasetError(f"非法 status {status!r}")
    ddir = dataset_dir(service, name, datasets_dir)
    path = ddir / "items" / f"{item_id}.json"
    if not path.exists():
        raise DatasetError(f"item 不存在: {service}/{name}/{item_id}")
    item = json.loads(path.read_text(encoding="utf-8"))
    old, new = STATUS_ORDER.index(item["status"]), STATUS_ORDER.index(status)
    if status != "archived" and new < old:
        raise DatasetError(f"状态只进不退: {item['status']} → {status} 被拒绝")
    item["status"] = status
    item.setdefault("metadata", {})["status_changed_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    path.write_text(json.dumps(item, ensure_ascii=False, indent=1), encoding="utf-8")
    _write_dataset_meta(ddir, service, name, changelog_note=f"{item_id}: {item['status']}→{status}")
    write_checksums(ddir)
    return item


def load_items(service: str | None = None, name: str | None = None,
               status: str | None = "active",
               datasets_dir: Path | str | None = None) -> list[dict[str, Any]]:
    """枚举 item；status=None 返回全部，否则只返回该状态。"""
    base = Path(datasets_dir) if datasets_dir else DATASETS_DIR
    if not base.exists():
        return []
    roots = ([dataset_dir(service, name, base)] if service and name
             else sorted(p for p in base.glob("*/*") if p.is_dir()))
    out: list[dict[str, Any]] = []
    for ddir in roots:
        for item in _iter_items(ddir):
            if status is None or item.get("status") == status:
                rel = ddir.relative_to(base)
                item.setdefault("metadata", {})
                item["metadata"].setdefault("_dataset", f"{rel.parts[0]}/{rel.parts[1]}")
                out.append(item)
    return out


def list_datasets(datasets_dir: Path | str | None = None) -> list[dict[str, Any]]:
    """数据集清单（读 dataset.json；无元信息目录跳过）。"""
    base = Path(datasets_dir) if datasets_dir else DATASETS_DIR
    if not base.exists():
        return []
    out = []
    for ddir in sorted(base.glob("*/*")):
        meta_path = ddir / "dataset.json"
        if meta_path.exists():
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            meta["_dir"] = str(ddir.relative_to(base))
            out.append(meta)
    return out


# ---------------------------------------------------------------------------
# 内部执行链路投影：item → runner.load_cases 同构用例字典（executor/checks 零改动）
# ---------------------------------------------------------------------------

def to_case(item: dict[str, Any]) -> dict[str, Any]:
    """active item → 用例字典。多轮 item 取首轮为 input.message（turns 随 metadata 保留）。"""
    meta = item.get("metadata") or {}
    case: dict[str, Any] = {
        "case_id": item["id"],
        "input": item["input"],
        "expected": item.get("expected") or {},
        "status": item["status"],
        "_file": f"{item['id']}.json",
    }
    for k in _CASE_META_KEYS:
        if meta.get(k) is not None:
            case[k] = meta[k]
    if item.get("ground_truth") is not None:
        case["ground_truth"] = item["ground_truth"]
    if meta.get("requires_tools"):
        case["requires_tools"] = meta["requires_tools"]
    if item.get("turns"):
        case["turns"] = item["turns"]
    return case


def load_active_cases(service: str | None = None, name: str | None = None,
                      datasets_dir: Path | str | None = None) -> list[dict[str, Any]]:
    """执行链路入口：active items → 用例字典列表（含多轮首轮投影）。"""
    return [to_case(it) for it in load_items(service, name, "active", datasets_dir)]


# ---------------------------------------------------------------------------
# 导入器：legacy cases / 骨架会话 → item
# ---------------------------------------------------------------------------

def item_from_case(case: dict[str, Any], source: str = "manual",
                   status: str = "active") -> dict[str, Any]:
    """runner.load_cases 形态 → item（迁移与 import-files 共用）。"""
    meta: dict[str, Any] = {"source": source}
    for k in _CASE_META_KEYS:
        if case.get(k) is not None:
            meta[k] = case[k]
    if case.get("requires_tools"):
        meta["requires_tools"] = case["requires_tools"]  # 用例顶层键收进 metadata（往返不丢）
    meta.setdefault("title", case.get("title"))
    if meta.get("title") is None:
        meta.pop("title")
    item: dict[str, Any] = {
        "id": case["case_id"],
        "input": case["input"],
        "turns": case.get("turns"),
        "ground_truth": case.get("ground_truth"),
        "expected": case.get("expected") or {},
        "status": status,
        "metadata": {k: v for k, v in meta.items() if v is not None},
    }
    return {k: v for k, v in item.items() if v is not None}


def item_from_skeleton(session: dict[str, Any], case_id: str | None = None,
                       status: str = "draft") -> dict[str, Any]:
    """T2 会话骨架（skeleton.py 产物）→ draft item（保守派生，参照 packager.derive_expected）。

    契约断言只给 no_error+AGENT_END 下限；语义金标缺省为空，留 G3 synth / 人审补齐。
    """
    sid = session["sid"]
    # sid 形如 "webui-xxx:gw-xxx"（含冒号），派生 id 前先清洗为路径安全形态
    cid = case_id or f"case_t2_{sid.replace(':', '_').replace('/', '_')[:40]}"
    _validated("dataset item id", cid)
    turns = session.get("turns") or []
    first_input = (turns[0].get("input") if turns else "") or session.get("first_input", "")
    tools = session.get("tool_names") or None
    expected: dict[str, Any] = {"no_error": True, "frames": {"AGENT_END": ">=1"}}
    if tools:
        expected["tool_calls"] = {"required": sorted(set(tools))}
    return {
        "id": cid,
        "input": {"message": first_input, "userId": "eval-t2"},
        "turns": [{"input": t.get("input", "")} for t in turns] or None,
        "ground_truth": session.get("final_output") or None,
        "expected": expected,
        "status": status,
        "metadata": {
            "source": "t2_skeleton", "title": f"会话骨架 {sid}",
            "category": "replay", "source_session": sid,
            "message_count": session.get("message_count"),
            "confidence": session.get("confidence", "medium"),
        },
    }
