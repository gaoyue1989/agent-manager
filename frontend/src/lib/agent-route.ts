// 发布助手专属路径前缀。必须做边界匹配：仅 /agent/release-agent 本身或其子路径，
// 不能吞掉 release-agentfoo 这类正常业务 short 名（否则会被错误反代到发布助手）。
export const RELEASE_AGENT_PREFIX = "/agent/release-agent";

export function isReleaseAgentPath(pathname: string): boolean {
  return pathname === RELEASE_AGENT_PREFIX || pathname.startsWith(`${RELEASE_AGENT_PREFIX}/`);
}
