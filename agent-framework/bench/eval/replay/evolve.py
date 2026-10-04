"""基线演进（--rebase，设计 §4.6/§10.4）与 e2e 夹具反哺（--export-e2e-fixtures）。

rebase：框架 prompt 组装类变更会推高漂移率——loose 回放期间 replay-llm 捕获被测服务
实际请求（/captured），用它们替换包内录制请求（响应 chunks 保持不变），生成带
rebase 留痕的新基线包。人审后替换基线，防止基线僵化。

export-e2e-fixtures：把 evalpack 的 LLM 交互转成 `agent-framework/e2e/mock/fixtures/llm/`
夹具格式（calls[].request/chunks 同构）+ registry.json 登记——真实流量沉淀为门禁轨夹具。
"""

import json
import shutil
import time
from pathlib import Path
from typing import Any

import httpx


def rebase_pack(pack_dir: str, replayer_port: int, out_dir: str,
                note: str = "") -> Path:
    """用回放期捕获的实际请求生成新基线包（结构/响应不变，请求字段替换）。"""
    src = Path(pack_dir)
    dst = Path(out_dir)
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)

    with httpx.Client(timeout=10.0) as c:
        captured = c.get(f"http://127.0.0.1:{replayer_port}/captured").json()["sessions"]

    replaced, kept = 0, 0
    for sid, items in (captured or {}).items():
        for item in items:
            if not item.get("request"):
                continue
            ipath = dst / "interactions" / "llm" / f"{item['id']}.json"
            if not ipath.exists():
                continue
            rec = json.loads(ipath.read_text(encoding="utf-8"))
            # 只替换请求形状（模型响应 chunks 原样保留——回放响应仍是录制件）
            rec["request"] = item["request"]
            rec["rebased"] = {"tier": item.get("tier"), "at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
            ipath.write_text(json.dumps(rec, ensure_ascii=False, indent=1), encoding="utf-8")
            replaced += 1
        # 会话骨架的 recorded_final 不变（响应未变）

    manifest_path = dst / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["rebase"] = {"replaced_requests": replaced, "kept_responses": True,
                          "note": note or "rebase：以回放期实际请求为新基线（响应保持录制件）",
                          "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    manifest["pack_id"] = dst.name
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")

    # 重算 CHECKSUMS + zip
    import hashlib
    import zipfile
    lines = []
    for p in sorted(dst.rglob("*")):
        if p.is_file() and p.name != "CHECKSUMS":
            lines.append(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(dst)}")
    (dst / "CHECKSUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")
    zip_path = dst.with_suffix(".zip")
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for p in sorted(dst.rglob("*")):
            if p.is_file():
                zf.write(p, p.relative_to(dst))
    print(f"[rebase] 新基线包: {dst}（替换请求 {replaced} 条，响应保持录制件）")
    return dst


def export_e2e_fixtures(pack_dir: str, out_dir: str | None = None) -> list[Path]:
    """evalpack → e2e/mock/fixtures/llm/*.json（每会话一个夹具，marker=会话 sid）。

    产物仅供人审后入库（夹具入库走 PR 人审，与用例同纪律）。
    """
    src = Path(pack_dir)
    fixtures_dir = Path(out_dir) if out_dir else (src.parent / "e2e-fixtures")
    fixtures_dir.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((src / "manifest.json").read_text(encoding="utf-8"))
    written: list[Path] = []
    for p in sorted((src / "sessions").glob("*.json")):
        sess = json.loads(p.read_text(encoding="utf-8"))
        calls = []
        for cid in sess.get("llm_calls") or []:
            ipath = src / "interactions" / "llm" / f"{cid}.json"
            if not ipath.exists():
                continue
            rec = json.loads(ipath.read_text(encoding="utf-8"))
            calls.append({"request": rec.get("request") or {},
                          "chunks": rec.get("chunks") or []})
        if not calls:
            continue
        fixture = {
            "scenario": sess["sid"],
            "model": (calls[0].get("request") or {}).get("model") or "recorded",
            "recordedAt": manifest.get("created_at"),
            "note": f"exported from evalpack {manifest['pack_id']} (session {sess['sid']}, "
                    f"confidence={sess.get('confidence')})",
            "calls": calls,
        }
        fpath = fixtures_dir / f"{sess['sid']}.json"
        fpath.write_text(json.dumps(fixture, ensure_ascii=False, indent=1), encoding="utf-8")
        written.append(fpath)
    print(f"[fixtures] 导出 {len(written)} 个 e2e 夹具 → {fixtures_dir}（入库走 PR 人审）")
    return written
