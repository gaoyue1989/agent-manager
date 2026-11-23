import type { McpConnectorView } from "@/lib/api";

export function parseToolPerms(cn: Pick<McpConnectorView, "permissions">): Record<string, string> {
  try {
    const tools = JSON.parse(cn.permissions || "{}")?.tools;
    if (tools && typeof tools === "object" && !Array.isArray(tools)) return tools;
  } catch { /* 非 JSON（历史脏数据）按未声明处理 */ }
  return {};
}

export function parseSnapshotTools(cn: Pick<McpConnectorView, "toolScope">): string[] {
  try {
    const tools = JSON.parse(cn.toolScope || "{}")?.tools;
    if (Array.isArray(tools)) return tools;
  } catch { /* 非 JSON（历史脏数据）按无快照处理 */ }
  return [];
}

export function parseDirectReturn(cn: Pick<McpConnectorView, "directReturn">): string[] {
  try {
    const tools: unknown = JSON.parse(cn.directReturn || "{}")?.tools;
    if (Array.isArray(tools)) {
      return tools.filter((tool): tool is string => typeof tool === "string" && tool.trim() !== "");
    }
  } catch {
    return [];
  }
  return [];
}
