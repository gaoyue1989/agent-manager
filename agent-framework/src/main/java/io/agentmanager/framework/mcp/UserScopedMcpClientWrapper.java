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
 * <p>meta 出站处理：userId 等既有 entries 原样下传（agentscope McpTool 写入
 * CallToolRequest._meta，协议通道）；{@code reqHeader:*}（chat 请求透传头原始值）
 * 在出站前剥离——透传值只走 HTTP header 单通道，不随 _meta 下发到任何 server
 * （McpMeta 是 per-turn 全 server 共享的）。
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
        var outboundMeta = stripForwardKeys(meta);
        if (resolved.isEmpty()) {
            // 全部 passthrough 跳过：静态 header 生效
            return delegate.callTool(toolName, arguments, outboundMeta);
        }
        var transportContext = McpTransportContext.create(Map.of(HEADER_KEY, resolved));
        return delegate.callTool(toolName, arguments, outboundMeta)
                .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
    }

    /**
     * 剥离 McpMeta 中的 {@code reqHeader:*} 键（chat 请求透传头的原始值）后再转交 delegate：
     * 透传值只走 HTTP header 单通道，不随 {@code CallToolRequest._meta} 下发到任何 server。
     * McpMeta 是 per-turn 全 server 共享的——不剥离会让 A server 声明的透传值漏进
     * B server（含未配置 userHeaders 的 server）的 _meta。
     */
    private static Map<String, Object> stripForwardKeys(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return meta;
        }
        boolean hasForwardKey = meta.keySet().stream()
            .anyMatch(k -> String.valueOf(k).startsWith(ChatRequestHeaders.META_KEY_PREFIX));
        if (!hasForwardKey) {
            return meta;   // 热路径无透传键：原引用透传，零拷贝
        }
        var filtered = new LinkedHashMap<String, Object>(meta.size());
        meta.forEach((k, v) -> {
            if (!String.valueOf(k).startsWith(ChatRequestHeaders.META_KEY_PREFIX)) {
                filtered.put(k, v);
            }
        });
        return filtered;
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
