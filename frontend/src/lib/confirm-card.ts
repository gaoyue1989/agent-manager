// HITL 确认卡纯逻辑（从 PermissionCard.tsx 抽出，便于 node:test 单测，模式同 assistant-events.ts）
export type ConfirmResult = { tool_call_id: string; confirmed: boolean };
export type ConfirmCard = {
  id: string;
  toolCalls: { tool_call_id: string; name: string; input: unknown }[];
  // expired：后端确认上下文不存在/已过期（confirm-stream 预检 404/410）——HITL 未批准故工具未执行，可安全重新发起
  status: "pending" | "submitting" | "resolved" | "unknown" | "expired";
  results?: ConfirmResult[];
  // D16 确认硬截止：后端透出的绝对失效时刻（ISO-8601，permission_ask.expires_at / history pendingConfirm.expires_at）；
  // 缺省 = 后端未启用截止（无限期可确认，现状行为）
  expiresAt?: string;
};

// 确认流失败归类：confirm_context_not_found（上下文过期，从未获批准）与 confirm_expired（D16 硬截止，按拒绝自动收尾）
// 均可断定「未执行该批准」；其余（含 409 confirm_already_consumed、网络中断）一律 unknown，无法排除已执行，禁重试。
export function classifyConfirmFailure(message: unknown): "expired" | "unknown" {
  return typeof message === "string"
    && (message.includes("confirm_context_not_found") || message.includes("confirm_expired")) ? "expired" : "unknown";
}

// 工具调用缺失 tool_call_id/名称或重复 id 时不构成可确认卡片 → unknown（禁提交防误批）
export function createConfirmCard(tools: unknown, expiresAt?: unknown): ConfirmCard {
  const toolCalls = Array.isArray(tools) ? tools.map((tool: any) => ({
    tool_call_id: typeof tool?.tool_call_id === "string" ? tool.tool_call_id : "",
    name: typeof tool?.name === "string" ? tool.name : "未知工具",
    input: tool?.input,
  })) : [];
  const valid = toolCalls.length > 0 && toolCalls.every((tool) => tool.tool_call_id && tool.name !== "未知工具")
    && new Set(toolCalls.map((tool) => tool.tool_call_id)).size === toolCalls.length;
  return {
    id: JSON.stringify(toolCalls.map((tool) => tool.tool_call_id).sort()),
    toolCalls,
    status: valid ? "pending" : "unknown",
    ...(typeof expiresAt === "string" && expiresAt ? { expiresAt } : {}),
  };
}

// D16 截止判定：已超失效时刻 → expired（禁提交；后端此时确认也会得到 410 confirm_expired 并按拒绝收尾）
export function isConfirmExpired(expiresAt: string | undefined, now: number = Date.now()): boolean {
  if (!expiresAt) return false;
  const t = Date.parse(expiresAt);
  return Number.isFinite(t) && now >= t;
}

// 剩余秒数（倒计时展示）；无截止时刻/解析失败返回 null（不显示倒计时）
export function confirmRemainingSeconds(expiresAt: string | undefined, now: number = Date.now()): number | null {
  if (!expiresAt) return null;
  const t = Date.parse(expiresAt);
  if (!Number.isFinite(t)) return null;
  return Math.max(0, Math.ceil((t - now) / 1000));
}

// 倒计时到点批处理：把 pending 且已超截止的卡片置 expired（其余卡片原样保留，返回原引用若无需变更）
export function expireDueConfirmCards<T extends { confirm?: ConfirmCard }>(messages: T[], now: number = Date.now()): T[] {
  let changed = false;
  const next = messages.map((m) => {
    const card = m.confirm;
    if (card && card.status === "pending" && isConfirmExpired(card.expiresAt, now)) {
      changed = true;
      return { ...m, confirm: { ...card, status: "expired" as const } };
    }
    return m;
  });
  return changed ? next : messages;
}

export function parseInput(input: unknown): unknown {
  if (typeof input !== "string") return input;
  try { return JSON.parse(input); } catch { return input; }
}

const sensitiveKey = /password|passwd|pwd|secret|token|credential|authorization|cookie|private.?key|api.?key|access.?key|密钥|密码/i;

// env 全量值默认遮掩，避免自定义变量名漏判；其他敏感字段递归遮掩，展开只影响展示。
export function maskInput(value: unknown, hide = false): unknown {
  if (Array.isArray(value)) return value.map((item) => maskInput(item, hide));
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
      key, maskInput(item, hide || key.toLowerCase() === "env" || sensitiveKey.test(key)),
    ]));
  }
  return hide ? "••••••（已遮掩）" : value;
}

export function hasEnv(value: unknown): boolean {
  return !!value && typeof value === "object" && Object.entries(value).some(([key, item]) =>
    key.toLowerCase() === "env" || hasEnv(item));
}
