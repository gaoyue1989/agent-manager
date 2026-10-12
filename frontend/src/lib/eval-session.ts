// 评测采集模式判定 + X-Eval-Session 头派生（纯函数，从 src/proxy.ts 抽出便于单测）。
// 设计 docs/design/agent-framework-eval-offline-record-replay-design.md 附录 C：
// 采集模式 = AGENT_INTERNAL_URL 指向 eval-collector 业务服务反代口(:18203/{ns})。
//
// issue #121 教训：判定原写作 `process.env.EVAL_COLLECTOR_AGENT_URL !== ""`——
// 该变量在仓库中从未被任何部署设置（检查 AGENT_INTERNAL_URL 的那一处），
// 未设置时 undefined !== "" 为 true，采集模式在本地/未注入环境被误开。
// 判定必须基于实际生效的 AGENT_INTERNAL_URL 且显式排除未设置/空串。
export function evalCollectorMode(agentInternalUrl: string | undefined): boolean {
  return typeof agentInternalUrl === "string" && agentInternalUrl.length > 0;
}

/**
 * 采集模式下注入 X-Eval-Session：使 collector 对外部依赖三协议（LLM/沙箱/MCP）的
 * 录制拿到与 HTTP 层一致的强关联主键。非采集模式或 sid 缺失时返回 null（不注入）。
 */
export function evalSessionHeader(
  agentInternalUrl: string | undefined,
  sid: string | undefined,
): string | null {
  return evalCollectorMode(agentInternalUrl) && sid ? sid : null;
}
