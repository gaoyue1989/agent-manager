"""diff 驱动用例生成（设计 §7.2 四道闸门的骨架实现）。

闸门①变更影响分析：git diff 文件路径 → 模块映射（纯规则，无 LLM）。
闸门②候选生成：LLM 按模块生成候选用例（few-shot 引用库内样例）。
闸门③校验：JSON Schema 必填字段校验 + 文本级去重 + 动态冒烟（在 flywheel.run 中执行）。
闸门④PR 人工审核：产物以 draft 状态写入 reports/{task_id}/gen_cases/，不入回归库。

需要 EVAL_LLM_BASE_URL / EVAL_LLM_API_KEY / EVAL_LLM_MODEL。
"""

import json
import re
import subprocess
from pathlib import Path
from typing import Any

import httpx

# 仓库路径前缀 → 功能模块（闸门①的映射规则）
_MODULE_RULES: list[tuple[str, str]] = [
    ("src/main/java/io/agentmanager/framework/tool/", "tool"),
    ("src/main/java/io/agentmanager/framework/mcp/", "mcp"),
    ("src/main/java/io/agentmanager/framework/sandbox/", "sandbox"),
    ("src/main/java/io/agentmanager/framework/controller/", "controller"),
    ("src/main/java/io/agentmanager/framework/service/", "service"),
    ("src/main/java/io/agentmanager/framework/storage/", "storage"),
    ("src/main/java/io/agentmanager/framework/config/", "config"),
    ("e2e/", "e2e"),
    ("bench/", "bench"),
]
# 高风险模块（工具与对外协议面变更 → 用例补充价值最大）
_HIGH_RISK = {"tool", "mcp", "controller"}

_GENERATE_PROMPT = """你是 agent-framework 的评测工程师。针对下述变更模块，生成 {n} 条黑盒评测用例。
被测 Agent 可用工具：{capabilities}
变更模块：{module}（涉及文件：{files}）
参考样例（保持同构的字段风格）：
{examples}

要求：
- 只通过公开 HTTP/SSE 接口可测（POST /threads/chat 对话 + 读取 SSE 帧），不侵入内部状态
- input.message 为真实用户口吻的中文指令；expected 只用机器可判键（frames/tool_calls/final_text_contains/no_error/error_contains）
- 输出 JSON 数组，每条字段：title, category(function|boundary), module, requires_tools(数组), input{{message}}, expected{{...}}, ground_truth
- 只输出 JSON 数组，不要多余文字"""


def analyze_diff(repo_dir: Path, since: str) -> dict[str, Any]:
    """闸门①：分析 since..HEAD 的变更文件，输出模块清单与风险等级。"""
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{since}..HEAD", "--", "."],
        cwd=repo_dir, capture_output=True, text=True, timeout=30,
    )
    if out.returncode != 0:
        raise RuntimeError(f"git diff 失败: {out.stderr[:200]}")
    modules: dict[str, list[str]] = {}
    for line in out.stdout.splitlines():
        path = line.strip()
        if not path:
            continue
        rel = path.replace("agent-framework/", "", 1) if path.startswith("agent-framework/") else path
        for prefix, module in _MODULE_RULES:
            if rel.startswith(prefix):
                modules.setdefault(module, []).append(rel)
                break
    risk = "high" if any(m in _HIGH_RISK for m in modules) else ("medium" if modules else "low")
    return {"since": since, "modules": modules, "risk": risk}


def _normalize(text: str) -> str:
    return re.sub(r"[\s，。？！,.?!]", "", text).lower()


async def generate_candidates(
    analysis: dict[str, Any], capabilities: set[str], library_inputs: list[str],
    cfg: dict[str, str], n_per_module: int, examples: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """闸门②+③（静态部分）：LLM 生成候选 + 必填字段校验 + 文本级去重。

    返回的候选用例 status=draft / source=diff_gen，冒烟由调用方执行。
    """
    if not analysis["modules"]:
        return []
    examples_txt = json.dumps(
        [{k: c[k] for k in ("title", "category", "input", "expected", "ground_truth")}
         for c in examples[:2]], ensure_ascii=False, indent=1)
    seen = {_normalize(x) for x in library_inputs}
    candidates: list[dict[str, Any]] = []

    for module, files in list(analysis["modules"].items())[:3]:  # 最多 3 个模块，防膨胀
        prompt = _GENERATE_PROMPT.format(
            n=n_per_module, capabilities=", ".join(sorted(capabilities)) or "（内置工具）",
            module=module, files=", ".join(files[:5]), examples=examples_txt)
        async with httpx.AsyncClient(timeout=httpx.Timeout(90.0)) as client:
            resp = await client.post(
                f"{cfg['base_url']}/chat/completions",
                headers={"Authorization": f"Bearer {cfg['api_key']}"},
                json={"model": cfg["model"], "temperature": 0.3,
                      "messages": [{"role": "user", "content": prompt}]},
            )
            if resp.status_code != 200:
                raise RuntimeError(f"用例生成接口返回 {resp.status_code}: {resp.text[:200]}")
        content = resp.json()["choices"][0]["message"]["content"]
        m = re.search(r"\[.*\]", content, re.S)
        if not m:
            continue
        try:
            items = json.loads(m.group(0))
        except json.JSONDecodeError:
            continue

        for i, item in enumerate(items):
            if not all(k in item for k in ("title", "input", "expected")):
                continue  # 闸门③：必填字段缺失直接丢弃
            if _normalize(item["input"].get("message", "")) in seen:
                continue  # 闸门③：与库内/已生成用例语义重复
            seen.add(_normalize(item["input"].get("message", "")))
            candidates.append({
                "case_id": f"case_gen_{module}_{i:02d}",
                "title": item["title"],
                "category": item.get("category", "function"),
                "priority": "medium",
                "module": item.get("module", module),
                "source": "diff_gen",
                "status": "draft",
                "requires_tools": item.get("requires_tools", []),
                "input": {"message": item["input"]["message"], "userId": "eval-test"},
                "hitl_policy": "none",
                "expected": item["expected"],
                "ground_truth": item.get("ground_truth", ""),
                "origin": {"since": analysis["since"], "files": files[:5]},
            })
    return candidates
