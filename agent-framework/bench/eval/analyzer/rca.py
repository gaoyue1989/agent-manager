"""失败分类（规则初筛，设计 §8 七类）+ 评测报告 Markdown 生成。

骨架期根因 = 规则初筛 + 证据摘要；LLM 深度根因为可选增强（--rca-llm）。
"""

import time
from typing import Any

from graders.correctness import JudgeUnavailable

# 规则初筛：按失败证据中的关键词映射七类（顺序即优先级，先命中先归类）
CATEGORY_RULES: list[tuple[str, list[str]]] = [
    ("工具调用类", ["tool", "工具", "mcp"]),
    ("输出交付类", ["present_url", "present_file", "file_ready", "白名单", "external URL"]),
    ("流程控制类", ["hitl", "permission", "confirm", "lease", "租约"]),
    ("协议接口类", ["sse", "frame", "帧", "stream", "流中断"]),
    ("沙箱相关类", ["sandbox", "沙箱"]),
    ("配置加载类", ["config", "oaf", "配置"]),
    ("模型交互类", ["model", "llm", "429", "401", "403", "rate limit", "timeout", "超时"]),
]

_CATEGORY_SUGGESTION = {
    "工具调用类": "检查工具注册（GET /tools）、MCP server 连通性与参数映射",
    "输出交付类": "检查 present_* 工具白名单配置（FILE_EXTERNAL_URL_PREFIXES）与 FileAssetStore 逻辑",
    "流程控制类": "检查 HITL 权限规则（permissions.tools ask/deny）与 turn 租约状态",
    "协议接口类": "对照 bench/eval/config/frame-mapping.json 核查帧序与配对完整性",
    "沙箱相关类": "检查 SANDBOX_ENABLED 与 OpenSandbox 服务连通性",
    "配置加载类": "检查 OAF 包 config.yaml 与环境变量注入",
    "模型交互类": "检查 LLM_BASE_URL/KEY 可用性、模型限流与超时配置",
}


def classify_failure(trace: dict[str, Any]) -> dict[str, Any]:
    """规则初筛：输入单条轨迹，返回 {category, evidence, suggestion}。"""
    evidences: list[str] = []
    if trace.get("error_info"):
        evidences.append(str(trace["error_info"]))
    for e in trace["view"].get("error_frames", []):
        evidences.append(str(e.get("error") or ""))
    for c in trace.get("check_results", []):
        if not c["passed"]:
            evidences.append(f"{c['name']}: {c['detail']}")

    text = " ".join(evidences).lower()
    for category, keywords in CATEGORY_RULES:
        if any(k.lower() in text for k in keywords):
            return {"category": category, "evidence": evidences[:5],
                    "suggestion": _CATEGORY_SUGGESTION[category]}
    if evidences:
        return {"category": "未分类", "evidence": evidences[:5],
                "suggestion": "人工分析轨迹（bench/eval/reports/ 下 trace 文件）"}
    return {"category": "通过", "evidence": [], "suggestion": ""}


def _fmt_case_row(trace: dict[str, Any]) -> str:
    checks = trace.get("check_results") or []
    failed = [f"{c['name']}({c['detail']})" for c in checks if not c["passed"]]
    check_txt = "; ".join(failed) if failed else "全部通过"
    usage = trace["view"].get("token_usage", {})
    return (f"| {trace['case_id']} | #{trace['repeat_no']} | {trace['status']} "
            f"| {trace['duration_ms']} | {usage.get('total', '-')} | {check_txt} |")


def build_report(task_id: str, meta: dict[str, Any], traces: list[dict[str, Any]],
                 skipped: list[dict[str, str]], analyses: list[dict[str, Any]] | None = None,
                 judge_results: dict[str, dict[str, Any]] | None = None) -> str:
    """生成评测报告 Markdown（含概览、明细表、失败清单与修复建议）。"""
    by_case: dict[str, list[dict[str, Any]]] = {}
    for t in traces:
        by_case.setdefault(t["case_id"], []).append(t)

    total = len(by_case)
    failed_cases = [cid for cid, ts in by_case.items()
                    if any(t["status"] != "success" or not t["checks_passed"] for t in ts)]
    pass_rate = (total - len(failed_cases)) / total if total else 0.0
    total_tokens = sum(t["view"]["token_usage"].get("total", 0) for t in traces)

    lines = [
        f"# 评测报告 {task_id}",
        "",
        f"- 提交: `{meta.get('commit', '-')}` · 触发: {meta.get('trigger', '-')} · "
        f"时间: {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 用例数: {total} · 通过: {total - len(failed_cases)} · 通过率: {pass_rate:.0%} · "
        f"执行: {len(traces)} 次 · token 合计: {total_tokens}",
        "",
        "## 结果明细",
        "",
        "| 用例 | 重复 | 状态 | 耗时ms | tokens | 检查项 |",
        "|---|---|---|---|---|---|",
    ]
    for cid, ts in sorted(by_case.items()):
        for t in sorted(ts, key=lambda x: x["repeat_no"]):
            lines.append(_fmt_case_row(t))

    if skipped:
        # issue #39：能力缺失跳过与环境分流跳过分开列——前者是被测 Agent 能力与用例声明
        # 失配的信号（用例未执行且不计入通过率，容易被静默忽略），需醒目提示
        cap_skips = [s for s in skipped if s.get("kind") == "capability"]
        env_skips = [s for s in skipped if s.get("kind") != "capability"]
        lines += ["", "## 跳过用例（不计入通过率）", ""]
        if cap_skips:
            lines += ["### ⚠️ 能力缺失（requires_tools 未满足：被测 Agent 缺少工具，"
                      "需排查能力清单或用例声明）", ""]
            lines += [f"- {s['case_id']}: {s['reason']}" for s in cap_skips]
            lines += [""]
        if env_skips:
            lines += ["### 环境未供给（requires_env，预期分流）", ""]
            lines += [f"- {s['case_id']}: {s['reason']}" for s in env_skips]

    if judge_results:
        lines += ["", "## 语义打分（judge，advisory 不阻断）", "",
                  "| 用例 | 得分 | 理由 |", "|---|---|---|"]
        lines += [f"| {cid} | {r['score']:.2f} | {r['reason']} |"
                  for cid, r in sorted(judge_results.items())]

    if failed_cases and analyses is not None:
        lines += ["", "## 问题清单（按规则初筛分类）", ""]
        for a in analyses:
            if a["case_id"] not in failed_cases:
                continue
            lines += [
                f"### {a['case_id']} — {a['category']}",
                "",
                f"- 证据: {a['evidence']}",
                f"- 修复建议: {a['suggestion']}",
                f"- 轨迹: `reports/{task_id}/traces/{a['case_id']}.json`",
                "",
            ]

    lines += ["", "---", "*由 bench/eval/flywheel.py 自动生成；judge 分数仅供参考，不作为合并门禁。*"]
    return "\n".join(lines)


async def llm_rca(case: dict[str, Any], trace: dict[str, Any],
                  cfg: dict[str, str]) -> str:
    """可选的 LLM 深度根因分析：输入失败证据摘要，输出一句话根因假设。"""
    import httpx

    failed = [c for c in trace.get("check_results", []) if not c["passed"]]
    evidence = "\n".join(
        [f"- {c['name']}: {c['detail']}" for c in failed]
        + [f"- error 帧: {e.get('error')}" for e in trace["view"].get("error_frames", [])])
    prompt = (
        "以下是 agent-framework 一次评测失败的证据摘要，请用不超过三句话给出最可能的根因"
        "（指向具体模块：tool/mcp/controller/service/storage/config/sandbox/LLM 封装）与修复方向。\n"
        f"用例: {case['case_id']}（{case.get('module', '')}）\n"
        f"用户输入: {case['input'].get('message', '')}\n"
        f"失败证据:\n{evidence or '- 无明确检查失败（可能超时/断流）'}"
    )
    async with httpx.AsyncClient(timeout=httpx.Timeout(60.0)) as client:
        resp = await client.post(
            f"{cfg['base_url']}/chat/completions",
            headers={"Authorization": f"Bearer {cfg['api_key']}"},
            json={"model": cfg["model"], "temperature": 0,
                  "messages": [{"role": "user", "content": prompt}]},
        )
        if resp.status_code != 200:
            raise JudgeUnavailable(f"RCA 接口返回 {resp.status_code}")
        return resp.json()["choices"][0]["message"]["content"].strip()
