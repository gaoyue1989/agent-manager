package io.agentmanager.framework.mcp;

import java.util.Map;

/**
 * MCP 用户级 header 注入规则（config.yaml userHeaders 节解析结果）。
 *
 * @param headers       header 名 → McpMeta entries key 的映射（如 X-User-Id ← userId；
 *                      取值源支持保留命名空间 reqHeader:<小写头名> = chat 请求透传头），
 *                      注入下游 HTTP header（headers 通道）
 * @param arguments     工具参数名 → McpMeta entries key 的映射
 *                      （mcp-header-arguments-injection-design），callTool 时合并进
 *                      JSON-RPC params.arguments（arguments 通道——下游 MCP 业务服务的
 *                      工具参数只从 arguments 取值，HTTP header 到不了业务代码）
 * @param denyOnMissing 缺值策略：true=deny（默认，fail-closed，拒绝调用）；
 *                      false=passthrough（跳过该 header，静态 auth.token 生效）。
 *                      仅作用于 headers 通道——arguments 通道缺值恒跳过注入
 */
public record UserHeaderRule(Map<String, String> headers,
                             Map<String, String> arguments,
                             boolean denyOnMissing) {

    /** 空规则：常驻装饰（_meta 剥离）但无注入映射——resolve 恒空，纯 passthrough */
    public static final UserHeaderRule EMPTY = new UserHeaderRule(Map.of(), Map.of(), true);
}
