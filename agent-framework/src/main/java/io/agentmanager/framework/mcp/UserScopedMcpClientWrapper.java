package io.agentmanager.framework.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP 用户级 header 装饰器：按调用方 McpMeta 动态注入下游 HTTP header。
 *
 * <p>语义对齐 deer-flow headers_from_context：
 * <ul>
 *   <li>静态 header（config.yaml auth.token）仅用于连接初始化与 tools/list 发现；</li>
 *   <li>业务工具调用按 {@link UserHeaderRule} 映射，从本次调用的 McpMeta 取值覆盖注入；</li>
 *   <li>缺值默认 fail-closed（on-missing: deny），绝不静默回退共享发现凭据；</li>
 *   <li>值无法作为 HTTP header（换行 / 首尾空白 / 非 ASCII）时一律拒绝，错误只含 key 名。</li>
 * </ul>
 *
 * <p>meta 原样下传：agentscope McpTool 会将其写入 CallToolRequest._meta（协议通道），
 * 用户身份因此在"HTTP header + _meta"两条通道同时生效。
 */
public class UserScopedMcpClientWrapper extends McpClientWrapper {

    /** 本次调用 header 集在 McpTransportContext 中的载体 key（进程内约定，随调用链传递） */
    public static final String HEADER_KEY = "io.agentmanager/mcpUserHeaders";

    private final McpClientWrapper delegate;
    private final UserHeaderRule rule;

    public UserScopedMcpClientWrapper(McpClientWrapper delegate, UserHeaderRule rule) {
        super(delegate.getName());
        this.delegate = delegate;
        this.rule = rule;
    }

    @Override
    public Mono<Void> initialize() {
        return delegate.initialize();
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        // 发现阶段走静态凭据，不注入用户 header
        return delegate.listTools();
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return callTool(toolName, arguments, null);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
        final Map<String, String> resolved;
        try {
            resolved = resolve(meta);
        } catch (McpUserHeaderException e) {
            return Mono.error(e);
        }
        if (resolved.isEmpty()) {
            // 全部 passthrough 跳过：静态 header 生效
            return delegate.callTool(toolName, arguments, meta);
        }
        var transportContext = McpTransportContext.create(Map.of(HEADER_KEY, resolved));
        return delegate.callTool(toolName, arguments, meta)
                .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
    }

    @Override
    public McpSchema.Tool getCachedTool(String toolName) {
        return delegate.getCachedTool(toolName);
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 按规则从 McpMeta 解析本次 header。
     *
     * @throws McpUserHeaderException 任一映射值无法作为 HTTP header；或缺值且 on-missing=deny
     */
    private Map<String, String> resolve(Map<String, Object> meta) {
        var resolved = new LinkedHashMap<String, String>();
        var missing = new ArrayList<String>();
        for (var entry : rule.headers().entrySet()) {
            var metaKey = entry.getValue();
            var value = meta != null ? meta.get(metaKey) : null;
            var text = value != null ? String.valueOf(value) : "";
            if (text.isBlank()) {
                if (rule.denyOnMissing()) {
                    missing.add(metaKey);
                }
                continue;
            }
            var reason = illegalHeaderValueReason(text);
            if (reason != null) {
                throw new McpUserHeaderException("MCP server '" + getName() + "': meta key '"
                        + metaKey + "' " + reason + ", so it cannot be sent as an HTTP header");
            }
            resolved.put(entry.getKey(), text);
        }
        if (!missing.isEmpty()) {
            throw new McpUserHeaderException("MCP server '" + getName()
                    + "' needs request-scoped value(s) [" + String.join(", ", missing)
                    + "]; provide them in McpMeta (RuntimeContext)");
        }
        return resolved;
    }

    /**
     * 判断值无法作为 HTTP header 的原因；合法返回 null。
     *
     * <p>换行/首尾空白会被传输层拒绝且异常信息可能回显完整值（进入模型可见的 tool 错误），
     * 非 ASCII 由底层 HTTP 库拒绝，其余控制字符由 JDK HttpRequest 拒绝（实测：除 HT 外
     * 0x00-0x1F 与 0x7F 均抛 IllegalArgumentException）——统一在此拦截，
     * 保证错误可行动且不泄漏值。
     */
    private static String illegalHeaderValueReason(String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return "contains a line break";
        }
        if (!value.equals(value.strip())) {
            return "has leading or trailing whitespace";
        }
        if (value.chars().anyMatch(c -> c > 127)) {
            return "contains non-ASCII characters";
        }
        if (value.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c == 0x7f)) {
            return "contains control characters";
        }
        return null;
    }
}
