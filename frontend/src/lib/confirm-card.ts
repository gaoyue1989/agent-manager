// HITL 确认卡纯逻辑（从 PermissionCard.tsx 抽出，便于 node:test 单测，模式同 assistant-events.ts）
export type ConfirmResult = { tool_call_id: string; confirmed: boolean };
export type ConfirmCard = {
  id: string;
  toolCalls: { tool_call_id: string; name: string; input: unknown }[];
  status: "pending" | "submitting" | "resolved" | "unknown";
  results?: ConfirmResult[];
};

// 工具调用缺失 tool_call_id/名称或重复 id 时不构成可确认卡片 → unknown（禁提交防误批）
export function createConfirmCard(tools: unknown): ConfirmCard {
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
  };
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
