package io.agentmanager.framework.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP 用户级 header 装饰器：按调用方 McpMeta 动态注入下游 HTTP header 与工具调用参数。
 *
 * <p>语义对齐 deer-flow headers_from_context：
 * <ul>
 *   <li>静态 header（config.yaml auth.token）仅用于连接初始化与 tools/list 发现；</li>
 *   <li>业务工具调用按 {@link UserHeaderRule} 映射，从本次调用的 McpMeta 取值覆盖注入；</li>
 *   <li>headers 通道缺值默认 fail-closed（on-missing: deny），绝不静默回退共享发现凭据；</li>
 *   <li>值无法作为 HTTP header（换行 / 首尾空白 / 非 ASCII）时一律拒绝，错误只含 key 名。</li>
 * </ul>
 *
 * <p>arguments 通道（mcp-header-arguments-injection-design）：规则 arguments 映射在
 * callTool 出站前合并进工具调用入参（JSON-RPC params.arguments）——下游 MCP 业务服务的
 * 工具参数只从 arguments 取值，HTTP header 到不了业务代码。缺值恒跳过（无 fail-closed）；
 * schema 门控（inputSchema 未声明且禁追加的参数不注入）+ args-win（调用方/模型显式参数
 * 优先，只补缺）。
 *
 * <p>meta 出站处理：userId 等既有 entries 原样下传（agentscope McpTool 写入
 * CallToolRequest._meta，协议通道）；{@code reqHeader:*}（chat 请求透传头原始值）
 * 在出站前剥离——透传值只走 HTTP header / arguments 单通道，不随 _meta 下发到任何 server
 * （McpMeta 是 per-turn 全 server 共享的）。
 */
    public class UserScopedMcpClientWrapper extends McpClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(UserScopedMcpClientWrapper.class);

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
        var outboundArguments = injectArguments(toolName, arguments, meta);
        var outboundMeta = stripForwardKeys(meta);
        if (resolved.isEmpty()) {
            // 全部 passthrough 跳过：静态 header 生效
            return delegate.callTool(toolName, outboundArguments, outboundMeta);
        }
        var transportContext = McpTransportContext.create(Map.of(HEADER_KEY, resolved));
        return delegate.callTool(toolName, outboundArguments, outboundMeta)
                .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
    }

    /**
     * 剥离 McpMeta 中的 {@code reqHeader:*} 键（chat 请求透传头的原始值）后再转交 delegate：
     * 透传值只走 HTTP header / arguments 单通道，不随 {@code CallToolRequest._meta} 下发到
     * 任何 server。McpMeta 是 per-turn 全 server 共享的——不剥离会让 A server 声明的透传值
     * 漏进 B server（含未配置 userHeaders 的 server）的 _meta。
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
            throw new McpUserHeaderException(missingDenyMessage(missing));
        }
        return resolved;
    }

    /**
     * 缺值 deny 报错文案：按取值源分叉，保证错误进入模型可见 tool 结果后仍可行动。
     *
     * <p>{@code reqHeader:*} 只能由调用方在 chat 请求头携带——模型无法写 McpMeta、
     * 用户也不知 McpMeta 为何物，统一报「provide them in McpMeta」会引发无效重试；
     * 该分支剥前缀回显头名，指引改为「chat 请求携带该 HTTP 头」。其余取值源
     * （userId 等服务端解析值）维持原 McpMeta 指引不变。
     */
    private String missingDenyMessage(List<String> missing) {
        var forwardHeaders = new ArrayList<String>();
        var otherKeys = new ArrayList<String>();
        for (var metaKey : missing) {
            if (metaKey.startsWith(ChatRequestHeaders.META_KEY_PREFIX)) {
                forwardHeaders.add(metaKey.substring(ChatRequestHeaders.META_KEY_PREFIX.length()));
            } else {
                otherKeys.add(metaKey);
            }
        }
        var parts = new ArrayList<String>(2);
        if (!forwardHeaders.isEmpty()) {
            parts.add("requires the chat request to carry HTTP header(s) ["
                    + String.join(", ", forwardHeaders) + "] (userHeaders on-missing: deny)");
        }
        if (!otherKeys.isEmpty()) {
            parts.add("needs request-scoped value(s) [" + String.join(", ", otherKeys)
                    + "]; provide them in McpMeta (RuntimeContext)");
        }
        return "MCP server '" + getName() + "' " + String.join("; ", parts);
    }

    /**
     * arguments 通道注入（mcp-header-arguments-injection-design §5.3）：按本 server
     * arguments 映射从 McpMeta 取值，schema 门控 + args-win 合并进工具调用入参。
     *
     * <p>与 headers 通道的语义差异：缺值恒跳过（无 fail-closed——工具若必需该参数，
     * 下游按自身 schema 校验报错，链路天然可定位）；值用捕获原串，不做 HTTP 合法性
     * 校验（JSON 报文无传输层约束）。
     *
     * @return 注入后的入参（防御性拷贝，入参可能不可变）；无可注入项时原引用返回
     *         （零拷贝热路径）
     */
    private Map<String, Object> injectArguments(String toolName, Map<String, Object> arguments,
                                                Map<String, Object> meta) {
        if (rule.arguments().isEmpty()) {
            return arguments;
        }
        Map<String, Object> merged = null;
        for (var entry : rule.arguments().entrySet()) {
            var argName = entry.getKey();
            var value = meta != null ? meta.get(entry.getValue()) : null;
            var text = value != null ? String.valueOf(value) : "";
            if (text.isBlank()) {
                // 缺值恒跳过：参数通道不做 fail-closed（设计 §5.4）
                continue;
            }
            if (arguments != null && arguments.containsKey(argName)) {
                // args-win：调用方/模型显式给出的参数优先，框架只补缺
                log.debug("MCP {}: tool '{}' argument '{}' already provided, injection skipped",
                    getName(), toolName, argName);
                continue;
            }
            if (!acceptsArgument(toolName, argName)) {
                // schema 门控：严格 schema 未声明该参数，不注入（防污染同 server 上
                // 不接受该参数的工具；DEBUG 只记名不记值）
                log.debug("MCP {}: tool '{}' schema does not accept argument '{}', injection skipped",
                    getName(), toolName, argName);
                continue;
            }
            if (merged == null) {
                merged = arguments != null ? new LinkedHashMap<>(arguments) : new LinkedHashMap<>();
            }
            merged.put(argName, text);
        }
        return merged != null ? merged : arguments;
    }

    /**
     * schema 门控：工具 inputSchema 是否接受该参数。
     *
     * <p>properties 命中 → 接受；未命中但 additionalProperties 非 false（含缺省，
     * JSON Schema 缺省即允许追加）→ 接受；未命中且显式 false → 拒绝。工具/ schema
     * 不可得（未缓存、查询异常）→ best-effort 接受（与「下游 schema 写漏但实际读参」
     * 的松散实现兼容，设计 §7.6）。
     */
    private boolean acceptsArgument(String toolName, String argName) {
        McpSchema.Tool tool;
        try {
            tool = getCachedTool(toolName);
        } catch (Exception e) {
            log.debug("MCP {}: cached tool '{}' lookup failed, injection proceeds best-effort",
                getName(), toolName);
            return true;
        }
        if (tool == null || tool.inputSchema() == null) {
            return true;
        }
        var schema = tool.inputSchema();
        if (schema.properties() != null && schema.properties().containsKey(argName)) {
            return true;
        }
        var additional = schema.additionalProperties();
        return additional == null || additional;
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
