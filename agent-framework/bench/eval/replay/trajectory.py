"""轨迹等价（A2）与漂移度量（A3）：录制轨迹 vs 回放轨迹的外部调用序列对比。

回放侧数据源 = replay-llm 的 /stats（按会话的 exact/normalized/drift/background 计数），
A2 门禁 = drift==0 且 missing==0（strict 模式下 normalized 计入通过，drift 不计）；
A3 advisory = drift_rate = drift / 总步数。
"""

import json
from typing import Any


def replayer_session_stats(stats: dict[str, Any], sid: str) -> dict[str, int]:
    """从 replay-llm /stats 提取单会话匹配统计（缺键按 0 处理）。"""
    s = (stats.get("sessions") or {}).get(sid) or {}
    keys = ("calls", "exact", "normalized", "drift", "background")
    return {k: int(s.get(k) or 0) for k in keys}


def evaluate_trajectory(sid: str, stats: dict[str, Any],
                        expected_llm_calls: int | None = None) -> dict[str, Any]:
    """单会话轨迹等价评估，产出 trajectory.json 的 case 级内容。

    - 主链路步数 = calls（replay-llm 消耗的录制调用数）；background 旁路不计入漂移
    - drift>0 → 等价失败（strict 即 case 失败；loose 只记录）
    - expected_llm_calls 提供时额外校验主链路步数（防少跑轮次）
    """
    s = replayer_session_stats(stats, sid)
    main = s["calls"]
    drifted = s["drift"]
    drift_rate = (drifted / main) if main else (1.0 if expected_llm_calls else 0.0)
    step_ok = drifted == 0
    count_ok = expected_llm_calls is None or main == expected_llm_calls
    steps = [
        {"step": i, "type": "llm", "name": "chat", "status": "replayed"}
        for i in range(main)
    ]
    for i in range(drifted):
        steps.append({"step": main + i, "type": "llm", "name": "chat",
                      "status": "drift", "detail": "请求形状与录制件失配（见 replayer-logs）"})
    return {
        "sid": sid,
        "stats": s,
        "expected_llm_calls": expected_llm_calls,
        "step_status": "pass" if (step_ok and count_ok) else "fail",
        "drift_rate": round(drift_rate, 4),
        "steps": steps,
    }


def run_drift_rate(trajectories: list[dict[str, Any]]) -> float:
    """run 级漂移率（advisory）：全部会话加权平均。"""
    total = sum(t["stats"]["calls"] for t in trajectories)
    drifted = sum(t["stats"]["drift"] for t in trajectories)
    return round(drifted / total, 4) if total else 0.0


def dump_json(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=1)
