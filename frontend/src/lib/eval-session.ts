// 评测采集模式判定（纯函数，从 src/proxy.ts 抽出便于单测）。
// 设计 docs/design/agent-framework-eval-offline-record-replay-design.md 附录 C：
// 采集模式 = 显式开关 EVAL_COLLECTOR_MODE=1，流量改经 eval-collector 反代口
// （EVAL_COLLECTOR_AGENT_URL，如 http://<collector>:18203/{ns}）透传 + 录制。
//
// issue #121 教训：早期判定写作 `process.env.EVAL_COLLECTOR_AGENT_URL !== ""`——
// 未设置时 undefined !== "" 为 true，采集注入在本地/未注入环境被误开（且当时
// cookie 无写入方，注入恒取不到值，缺陷被掩盖）。现语义：**显式开关 + 目标必达**。
export function evalCollectorMode(mode: string | undefined): boolean {
  return mode === "1";
}

/**
 * 采集目标地址：开关开启且已设置时返回；否则 null（调用方退回普通代理且不注入）。
 * 对应 proxy.ts 里「开关开启但目标未设置 → console.error 告警 + 退回」的兜底分支。
 */
export function evalCollectorTarget(
  mode: string | undefined,
  agentUrl: string | undefined,
): string | null {
  return evalCollectorMode(mode) && agentUrl ? agentUrl : null;
}
