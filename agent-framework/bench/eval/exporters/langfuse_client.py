"""langfuse 导出器：标准条目 → dataset items（本地 JSONL + 可选 REST 推送）。

API 依据 langfuse 官方文档（experiments data-model / datasets）：
  POST /api/public/dataset-items，Basic 认证（public key: secret key），
  按 id upsert——id 确定性即幂等，重复推送不产生重复 item。
  注意：dataset-run-items REST 已弃用，实验执行走 SDK/UI（本模块不做）。

凭据约定（与 langfuse 官方 SDK 环境变量同名）：
  LANGFUSE_HOST / LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY
密钥只从环境变量读取，任何导出物（JSONL/日志）不得包含密钥。
"""

import base64
import json
import os
import time
from pathlib import Path
from typing import Any

import httpx

_GENERATOR = "agent-manager-eval/exporters"
_RETRYABLE_STATUS = frozenset({429, 500, 502, 503, 504})


class LangfuseError(Exception):
    """环境/参数层错误（缺凭据等），推送前的失败直接终止。"""


def resolve_creds() -> tuple[str, str, str]:
    """从环境读取 (base_url, pk, sk)；缺任一即抛 LangfuseError（不落盘不打印值）。"""
    base = os.environ.get("LANGFUSE_HOST", "")
    pk = os.environ.get("LANGFUSE_PUBLIC_KEY", "")
    sk = os.environ.get("LANGFUSE_SECRET_KEY", "")
    missing = [n for n, v in (("LANGFUSE_HOST", base), ("LANGFUSE_PUBLIC_KEY", pk),
                              ("LANGFUSE_SECRET_KEY", sk)) if not v]
    if missing:
        raise LangfuseError(f"缺少环境变量: {', '.join(missing)}（凭据只放环境/.env.secrets）")
    return base.rstrip("/"), pk, sk


def external_id(item: dict[str, Any], id_prefix: str | None = None) -> str:
    """外部确定性 id：无前缀用 item id；带前缀（如 pack_id）下划线拼接（项目级 upsert 唯一）。"""
    return f"{id_prefix}_{item['id']}" if id_prefix else item["id"]


def build_item(item: dict[str, Any], dataset_name: str,
               id_prefix: str | None = None) -> dict[str, Any]:
    """标准条目 → langfuse dataset item 请求体。"""
    meta = item.get("metadata") or {}
    inp: dict[str, Any] = {"message": item["input_message"]}
    if item.get("turns"):
        inp["turns"] = item["turns"]
    payload: dict[str, Any] = {
        "id": external_id(item, id_prefix),
        "datasetName": dataset_name,
        "input": inp,
        "metadata": {"generator": _GENERATOR,
                     **{k: v for k, v in meta.items() if v is not None}},
    }
    ground_truth = item.get("ground_truth") or item.get("final_output")
    if ground_truth:
        payload["expectedOutput"] = {"answer": ground_truth}
    return payload


def write_items(path: str | Path, payloads: list[dict[str, Any]]) -> int:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for p in payloads:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    return len(payloads)


def _auth_header(pk: str, sk: str) -> str:
    return "Basic " + base64.b64encode(f"{pk}:{sk}".encode()).decode()


def ensure_dataset(base_url: str, public_key: str, secret_key: str, name: str) -> None:
    """确保数据集存在：dataset-items API 不自动建集（实测 404 Dataset not found）。

    2xx=新建成功；409=已存在（幂等目标态）；其余抛 LangfuseError。
    """
    with httpx.Client(timeout=httpx.Timeout(30.0),
                      headers={"Authorization": _auth_header(public_key, secret_key)}) as client:
        try:
            resp = client.post(f"{base_url}/api/public/datasets", json={"name": name})
        except httpx.TransportError as e:
            raise LangfuseError(f"dataset 创建传输失败: {e}") from e
        if resp.is_success or resp.status_code == 409:
            return
        raise LangfuseError(f"dataset 创建失败 http {resp.status_code}: {resp.text[:200]}")


def push(base_url: str, public_key: str, secret_key: str,
         payloads: list[dict[str, Any]], max_retries: int = 3,
         sleep: Any = time.sleep) -> dict[str, Any]:
    """确保数据集存在后批量 upsert。单条失败不阻断批量；429/5xx/网络错误退避重试，4xx 不重试。

    返回 {"total", "upserted", "failed": [{"id", "status", "detail"}]}——调用方据 failed 决定退出码。
    """
    dataset_name = payloads[0].get("datasetName") if payloads else None
    if not dataset_name:
        raise LangfuseError("payload 缺少 datasetName")
    ensure_dataset(base_url, public_key, secret_key, dataset_name)
    result: dict[str, Any] = {"total": len(payloads), "upserted": 0, "failed": []}
    result: dict[str, Any] = {"total": len(payloads), "upserted": 0, "failed": []}
    with httpx.Client(timeout=httpx.Timeout(30.0),
                      headers={"Authorization": _auth_header(public_key, secret_key)}) as client:
        for p in payloads:
            ok = False
            detail = ""
            for attempt in range(max_retries):
                try:
                    resp = client.post(f"{base_url}/api/public/dataset-items", json=p)
                except httpx.TransportError as e:
                    detail = f"transport: {e}"
                    sleep(0.5 * (2 ** attempt))
                    continue
                if resp.status_code in _RETRYABLE_STATUS:
                    detail = f"http {resp.status_code}"
                    sleep(0.5 * (2 ** attempt))
                    continue
                if resp.is_success:
                    ok = True
                    detail = ""
                else:
                    # 4xx（鉴权/参数类）重试无意义，立即判失败
                    detail = f"http {resp.status_code}: {resp.text[:200]}"
                break
            if ok:
                result["upserted"] += 1
            else:
                result["failed"].append({"id": p.get("id"), "detail": detail or "retries exhausted"})
    return result


def export(items: list[dict[str, Any]], out_dir: str | Path, dataset_name: str,
           id_prefix: str | None = None, do_push: bool = False) -> dict[str, Any]:
    """完整导出：本地 items.jsonl（可 diff 可人审）→ 可选推送。返回执行摘要。"""
    payloads = [build_item(it, dataset_name, id_prefix) for it in items]
    out = Path(out_dir)
    local = out / "langfuse-items.jsonl"
    count = write_items(local, payloads)
    summary: dict[str, Any] = {"local": str(local), "items": count}
    if do_push:
        base, pk, sk = resolve_creds()
        summary["push"] = push(base, pk, sk, payloads)
    return summary
