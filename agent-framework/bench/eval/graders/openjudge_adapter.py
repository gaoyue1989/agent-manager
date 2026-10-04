"""OpenJudge 适配器（设计 §5.2 主选 judge；依赖钉版 py-openjudge==0.2.2，见 pyproject.toml）。

lazy import：运行环境未安装 py-openjudge 时 openjudge_available() 返回 False，
调用方回落 graders/correctness.py 的直评形态——selftest / 离线 e2e / CI 零重依赖。

维度契约与趋势轨一致：{"score": 0~1, "reason": str}，额外携带 judge_engine 与 raw_score
（OpenJudge 原始分 1~5 制，threshold=3 为及格线；归一化 = (raw-1)/4 截断到 [0,1]）。
"""

import asyncio
from typing import Any

_ENGINE_VERSION = "openjudge-0.2.2"


def openjudge_available() -> bool:
    try:
        import openjudge.graders.common  # noqa: F401
        return True
    except ImportError:
        return False


def _normalize(raw: float, threshold: float) -> float:
    """OpenJudge 1~5 原始分 → 0~1（threshold 语义保留在 raw_score/metadata）。"""
    return max(0.0, min(1.0, (float(raw) - 1.0) / 4.0))


async def judge_correctness_openjudge(case: dict[str, Any], trace: dict[str, Any],
                                      cfg: dict[str, str]) -> dict[str, Any]:
    """用 OpenJudge CorrectnessGrader 打分；cfg 与 graders.correctness.judge_from_env 同形。"""
    from openjudge.graders.common import CorrectnessGrader
    from openjudge.graders.llm_grader import OpenAIChatModel

    model = OpenAIChatModel(model=cfg["model"], api_key=cfg["api_key"], base_url=cfg["base_url"])
    grader = CorrectnessGrader(model=model, language="zh")
    result = await grader.aevaluate(
        query=case.get("input", {}).get("message", ""),
        response=(trace.get("view") or {}).get("final_output", ""),
        reference_response=case.get("ground_truth") or "（无参考答案，按任务描述常识判断）",
    )
    raw = float(getattr(result, "score", 0.0))
    return {
        "score": _normalize(raw, 3.0),
        "reason": str(getattr(result, "reason", "")),
        "judge_engine": _ENGINE_VERSION,
        "raw_score": raw,
    }


def run_async(coro: Any) -> Any:
    """同步包装（供脚本/调试）。"""
    return asyncio.run(coro)
