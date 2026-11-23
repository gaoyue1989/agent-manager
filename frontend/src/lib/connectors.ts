import type { McpConnectorView } from "@/lib/api";

function connectorJson(raw: string | undefined): Record<string, unknown> {
  try {
    return JSON.parse(raw || "{}") ?? {};
  } catch {
    return {};
  }
}

export function parseToolPerms(cn: Pick<McpConnectorView, "permissions">): Record<string, string> {
  const tools = connectorJson(cn.permissions).tools;
  if (tools && typeof tools === "object" && !Array.isArray(tools)) return tools as Record<string, string>;
  return {};
}

export function parseSnapshotTools(cn: Pick<McpConnectorView, "toolScope">): string[] {
  const tools = connectorJson(cn.toolScope).tools;
  return Array.isArray(tools) ? tools : [];
}

export function parseDirectReturn(cn: Pick<McpConnectorView, "directReturn">): string[] {
  const tools = connectorJson(cn.directReturn).tools;
  if (!Array.isArray(tools)) return [];
  return tools.filter((tool): tool is string => typeof tool === "string" && tool.trim() !== "");
}
