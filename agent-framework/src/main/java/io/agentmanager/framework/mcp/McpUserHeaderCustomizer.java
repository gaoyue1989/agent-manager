package io.agentmanager.framework.mcp;

import java.util.Map;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;

/**
 * MCP per-request HTTP header 注入。
 *
 * <p>SDK（mcp-core 0.17.x）在每次发送消息时从调用链的 Reactor Context 读取
 * {@link io.modelcontextprotocol.common.McpTransportContext#KEY} 并透传为本回调的
 * transportContext 参数（HttpClientStreamableHttpTransport / HttpClientSseClientTransport
 * 均已核实）。{@link UserScopedMcpClientWrapper} 在 callTool 链上 contextWrite 写入本次
 * 调用解析好的 header 集，本回调负责落到 HTTP 请求。
 *
 * <p>无值时（连接初始化 / tools/list / passthrough 调用）不做任何修改，静态 header
 * （config.yaml auth.token）生效——与 deer-flow "静态 headers 仅用于启动发现" 语义一致。
 */
public final class McpUserHeaderCustomizer {

    private McpUserHeaderCustomizer() {
    }

    /**
     * @return 注册到 McpClientBuilder.httpRequestCustomizer 的 per-request 定制器
     */
    public static McpSyncHttpClientRequestCustomizer userHeader() {
        return (builder, method, uri, body, transportContext) -> {
            if (transportContext == null) {
                return;
            }
            if (transportContext.get(UserScopedMcpClientWrapper.HEADER_KEY) instanceof Map<?, ?> headers) {
                for (var entry : headers.entrySet()) {
                    if (entry.getKey() instanceof String name && entry.getValue() instanceof String value) {
                        // setHeader：替换同名 header（大小写不敏感），避免与静态 header 重复发送
                        builder.setHeader(name, value);
                    }
                }
            }
        };
    }
}
