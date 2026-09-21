package io.agentmanager.framework.mcp;

import java.util.Map;

/**
 * MCP 用户级 header 注入规则（config.yaml userHeaders 节解析结果）。
 *
 * @param headers       header 名 → McpMeta entries key 的映射（如 X-User-Id ← userId）
 * @param denyOnMissing 缺值策略：true=deny（默认，fail-closed，拒绝调用）；
 *                      false=passthrough（跳过该 header，静态 auth.token 生效）
 */
public record UserHeaderRule(Map<String, String> headers, boolean denyOnMissing) {
}
