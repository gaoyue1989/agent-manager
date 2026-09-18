// 工具调用事件纯逻辑（node:test 覆盖，模式同 confirm-card.ts）
export type ToolMessage = {
  role: string;
  content: string;
  pending?: boolean;
  toolCallId?: string;
  /** 运行时终态（TOOL_RESULT_END.state）：SUCCESS/ERROR/DENIED/INTERRUPTED；历史回放无此字段 */
  state?: string;
};

/** 工具步骤展示状态：running=执行中；awaiting=等待人工确认；unknown=无执行状态（历史回放/中断），不得臆造成败 */
export type ToolStatus = "running" | "awaiting" | "success" | "error" | "denied" | "interrupted" | "unknown";

// SDK ToolResultState 的终态子集（RUNNING 非终态，不落库）
const TERMINAL_STATES = new Set(["SUCCESS", "ERROR", "DENIED", "INTERRUPTED"]);
// 前端口径的等待确认标记（非后端 state）：permission_ask 时写入，批准/拒绝后的真实 state 会覆盖它
const AWAITING = "AWAITING_CONFIRM";

// 状态大小写不统一是两条链路的既有事实，必须归一化后比较：
//  - 实时流 TOOL_RESULT_END：SDK 枚举名，大写（SUCCESS/DENIED/…）
//  - 历史回放（AgentState 的 tool_result.state）：序列化值，小写（success/denied/…）
const norm = (state: string) => state.toUpperCase();

/** 官方 ToolCallState.ASKING：AgentState 中工具已挂起、等待人工确认（无 TTL） */
const isAwaiting = (state?: string) =>
  !!state && (state === AWAITING || norm(state) === "ASKING");

const isTerminal = (state?: string) => !!state && TERMINAL_STATES.has(norm(state));

/**
 * TOOL_RESULT_END → 给对应工具步骤写终态。
 * - 只认后端真实 state：非 SUCCESS 不再被当作成功（原实现把 DENIED/ERROR/INTERRUPTED 统压成「执行结束」）
 * - RUNNING 非终态：忽略，保持执行中
 * - 认领条件为「尚无终态」：等待确认中的步骤同样可被写终态（重放/重复结果不会覆盖首次终态）
 * - 认领不要求 pending：permission_ask 暂停/流结束兜底会清 pending，恢复后仍需写入真实结果
 */
export function completeToolCall<T extends ToolMessage>(
  messages: T[],
  event: { toolCallId?: unknown; toolCallName?: unknown; state?: unknown },
): T[] {
  if (typeof event.toolCallId !== "string" || !event.toolCallId) return messages;
  const state = typeof event.state === "string" ? event.state : undefined;
  if (state && norm(state) === "RUNNING") return messages;
  return messages.map((message) =>
    message.role === "tool" && message.toolCallId === event.toolCallId && !isTerminal(message.state)
      ? { ...message, pending: false, ...(isTerminal(state) ? { state } : {}) }
      : message,
  );
}

/**
 * 执行段结束兜底（SSE 流关闭）：仍在转圈的步骤不再转圈。
 * 覆盖用户拒绝、流中断等不产生 TOOL_RESULT_END 的情况——清 pending 后渲染中性「无执行状态」，绝不显示成功。
 */
export function settlePendingToolCalls<T extends ToolMessage>(messages: T[]): T[] {
  return messages.map((message) =>
    message.role === "tool" && message.pending ? { ...message, pending: false } : message,
  );
}

/**
 * permission_ask 暂停：该批工具在等待人工批准，标 awaiting（不再是「执行中」——没在执行，在等人）。
 * 批准/拒绝后的 TOOL_RESULT_END 会覆盖为真实终态；无终态（如用户拒绝且后端不发结果事件）由调用方收尾。
 */
export function markAwaitingConfirm<T extends ToolMessage>(messages: T[], toolCallIds: string[]): T[] {
  const ids = new Set(toolCallIds);
  return messages.map((message) =>
    message.role === "tool" && message.toolCallId && ids.has(message.toolCallId) && !isTerminal(message.state)
      ? { ...message, pending: false, state: AWAITING }
      : message,
  );
}

/** 被拒绝且无结果事件的步骤收尾（用户点「拒绝」后确认卡已终结，不该继续显示「待确认」） */
export function markDeniedWithoutResult<T extends ToolMessage>(messages: T[], toolCallIds: string[]): T[] {
  const ids = new Set(toolCallIds);
  return messages.map((message) =>
    message.role === "tool" && message.toolCallId && ids.has(message.toolCallId) && !isTerminal(message.state)
      ? { ...message, pending: false, state: "DENIED" }
      : message,
  );
}

/** 工具步骤展示状态：真实终态 > 等待确认 > 执行中 > 无执行状态（不假设成功） */
export function toolStatus(message: ToolMessage): ToolStatus {
  if (isTerminal(message.state)) return message.state!.toLowerCase() as ToolStatus;
  // 等待人工确认有两种来源：官方 ToolCallState.ASKING（历史回放，来自 AgentState）
  // 与前端 permission_ask 时写入的 AWAITING_CONFIRM（实时流）
  if (isAwaiting(message.state)) return "awaiting";
  // 官方 ToolResultState.RUNNING（快照时工具仍在执行）：维持执行中展示，不降级为中性
  if (message.state && norm(message.state) === "RUNNING") return "running";
  if (message.pending) return "running";
  return "unknown";
}
