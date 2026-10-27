"""G3 轨迹合成 golden（设计 dataset-hub §数据获取通道：无真实流量冷启动 / 语义金标补齐）。

素材：T2 会话骨架（skeleton.py 产物）/ evalpack 会话。LLM 从真实会话蒸馏
「独立指令 input + 行为标准 expected_output」——不逐字复制会话原文（防敏感内容
如哨兵值/内网路径外泄），金标是质量标准的陈述而非原文。
四道闸门（与 generator.py 同原则）：
  ①字段校验（input/expected_output 非空）②归一化去重（对库内 + 批内）
  ③动态冒烟（可选，调用方执行）④draft 落库（source=g3_synth，人审转正）

需要 EVAL_LLM_BASE_URL / EVAL_LLM_API_KEY / EVAL_LLM_MODEL。
"""

import hashlib
import json
import re
from typing import Any

import httpx

_SYNTH_PROMPT = """你是 Agent 评测工程师。从下述真实会话素材中蒸馏 {n} 条可复用的评测 golden。
素材（用户真实输入 + 助手终答摘要）：{material}

要求：
- input：把用户真实意图改写为一条独立、自包含的中文指令；**不得**保留原会话中的
  具体文件名/哨兵值/时间戳等一次性细节
- expected_output：回答质量的行为标准（应做什么/不得做什么），不是答案原文复写
- category: function（常规能力）或 boundary（边界与约束）
- 输出 JSON 数组，每条字段：input, expected_output, category；只输出 JSON 数组"""


def _normalize(text: str) -> str:
    return re.sub(r"[\s，。？！,.?!]", "", text).lower()


def _sid_tag(sid: str) -> str:
    return hashlib.sha1(sid.encode()).hexdigest()[:8]


def build_material(sessions: list[dict[str, Any]], limit: int) -> list[dict[str, str]]:
    """骨架 → 素材（截断防爆 prompt；无终答的会话跳过——金标无处锚定）。"""
    material = []
    for s in sessions:
        first = (s.get("turns") or [{}])[0].get("input", "")
        final = s.get("final_output") or ""
        if not first or not final:
            continue
        material.append({"sid": s["sid"], "input": first[:400], "final": final[:600]})
        if len(material) >= limit:
            break
    return material


async def synthesize_goldens(material: list[dict[str, str]], existing_inputs: list[str],
                             cfg: dict[str, str]) -> list[dict[str, Any]]:
    """闸门①②：LLM 蒸馏 + 必填校验 + 归一化去重（对库内与批内）。"""
    if not material:
        return []
    material_txt = json.dumps(
        [{"用户输入": m["input"], "助手终答": m["final"]} for m in material],
        ensure_ascii=False, indent=1)
    seen = {_normalize(x) for x in existing_inputs}
    async with httpx.AsyncClient(timeout=httpx.Timeout(120.0)) as client:
        resp = await client.post(
            f"{cfg['base_url']}/chat/completions",
            headers={"Authorization": f"Bearer {cfg['api_key']}"},
            json={"model": cfg["model"], "temperature": 0.3,
                  "messages": [{"role": "user",
                                "content": _SYNTH_PROMPT.format(n=len(material),
                                                                material=material_txt)}]},
        )
        if resp.status_code != 200:
            raise RuntimeError(f"合成接口返回 {resp.status_code}: {resp.text[:200]}")
    content = resp.json()["choices"][0]["message"]["content"]
    m = re.search(r"\[.*\]", content, re.S)
    if not m:
        raise RuntimeError(f"合成输出无 JSON 数组: {content[:200]}")
    out: list[dict[str, Any]] = []
    for i, item in enumerate(json.loads(m.group(0))):
        text_in = (item.get("input") or "").strip()
        text_out = (item.get("expected_output") or "").strip()
        if not text_in or not text_out:
            continue  # 闸门①：缺字段丢弃
        if _normalize(text_in) in seen:
            continue  # 闸门②：与库内/批内语义重复
        seen.add(_normalize(text_in))
        src = material[min(i, len(material) - 1)]["sid"]
        out.append({
            "case_id": f"case_synth_{_sid_tag(src)}_{i:02d}",
            "title": f"合成 golden（素材 {src[:32]}）",
            "category": item.get("category", "function"),
            "module": "synth",
            "source": "g3_synth",
            "status": "draft",
            "input": {"message": text_in, "userId": "eval-synth"},
            "expected": {"no_error": True},
            "ground_truth": text_out,
            "origin": {"source_session": src, "material_count": len(material)},
        })
    return out
