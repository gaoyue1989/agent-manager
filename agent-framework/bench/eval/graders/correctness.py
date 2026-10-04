"""正确性 judge：OpenAI 兼容端点打分（0~1）。

judge 引擎选择（EVAL_JUDGE_ENGINE，缺省 auto）：
  auto      py-openjudge 可用（钉版 ==0.2.2，见 pyproject.toml）→ OpenJudge CorrectnessGrader；
            否则回落本模块直评形态（同一契约：{"score": 0~1, "reason"}）
  direct    强制直评（LLM-as-judge 单轮）
  openjudge 强制 OpenJudge（不可用即抛 JudgeUnavailable）

需要 EVAL_LLM_BASE_URL / EVAL_LLM_API_KEY / EVAL_LLM_MODEL。
"""

import json
import os
import re
from typing import Any

import httpx

_SYSTEM_PROMPT = (
    "你是严格的评测裁判。根据任务描述、参考答案（ground_truth）与被测 Agent 的实际回复，"
    "评判回复是否正确完成任务。只输出 JSON：{\"score\": 0~1 的数字, \"reason\": \"一句话理由\"}。"
    "回复与参考答案语义一致且无事实错误得 1.0；部分正确按比例给分；完全错误或拒绝回答得 0。"
)


class JudgeUnavailable(Exception):
    """judge 配置缺失或调用失败（调用方应捕获并降级为仅确定性检查）。"""


def judge_from_env() -> dict[str, str] | None:
    """从环境读取 judge 配置；未配置返回 None（调用方降级）。"""
    cfg = {
        "base_url": os.environ.get("EVAL_LLM_BASE_URL", "").rstrip("/"),
        "api_key": os.environ.get("EVAL_LLM_API_KEY", ""),
        "model": os.environ.get("EVAL_LLM_MODEL", ""),
    }
    return cfg if all(cfg.values()) else None


async def judge_correctness(case: dict[str, Any], trace: dict[str, Any],
                            cfg: dict[str, str]) -> dict[str, Any]:
    """对单条用例轨迹打分，返回 {"score": float, "reason": str}（引擎选择见模块 docstring）。"""
    engine = os.environ.get("EVAL_JUDGE_ENGINE", "auto").lower()
    if engine in ("auto", "openjudge"):
        from graders.openjudge_adapter import openjudge_available, judge_correctness_openjudge
        if openjudge_available():
            return await judge_correctness_openjudge(case, trace, cfg)
        if engine == "openjudge":
            raise JudgeUnavailable("EVAL_JUDGE_ENGINE=openjudge 但 py-openjudge 未安装（pip install py-openjudge==0.2.2）")
    return await _judge_direct(case, trace, cfg)


async def _judge_direct(case: dict[str, Any], trace: dict[str, Any],
                        cfg: dict[str, str]) -> dict[str, Any]:
    """直评形态（骨架期实现；OpenJudge 不可用时的回落）。"""
    user_msg = (
        f"任务描述：{case.get('title', '')}\n"
        f"用户输入：{case['input'].get('message', '')}\n"
        f"参考答案：{case.get('ground_truth', '（无，按常识判断）')}\n"
        f"实际回复：{trace['view'].get('final_output', '')[:4000]}"
    )
    async with httpx.AsyncClient(timeout=httpx.Timeout(60.0)) as client:
        resp = await client.post(
            f"{cfg['base_url']}/chat/completions",
            headers={"Authorization": f"Bearer {cfg['api_key']}"},
            json={
                "model": cfg["model"],
                "messages": [{"role": "system", "content": _SYSTEM_PROMPT},
                             {"role": "user", "content": user_msg}],
                "temperature": 0,
            },
        )
        if resp.status_code != 200:
            raise JudgeUnavailable(f"judge 接口返回 {resp.status_code}: {resp.text[:200]}")
        content = resp.json()["choices"][0]["message"]["content"]

    # 防御解析：优先整体 JSON，失败则提取首个 {...} 片段
    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        m = re.search(r"\{.*\}", content, re.S)
        if not m:
            raise JudgeUnavailable(f"judge 输出无法解析: {content[:200]}")
        data = json.loads(m.group(0))
    score = float(data.get("score", 0.0))
    return {"score": max(0.0, min(1.0, score)), "reason": str(data.get("reason", ""))}
