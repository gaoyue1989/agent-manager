package io.agentmanager.framework.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import io.agentscope.core.agent.RuntimeContext;

/**
 * chat / confirm / AGUI 请求级透传 header 集（mcp-chat-header-forward-design）。
 *
 * <p>取值源由包配置驱动：`mcp-configs/{server}/config.yaml` 的 {@code userHeaders.headers}
 * 以 {@code reqHeader:<小写头名>} 声明映射，各 server 声明求并集即捕获名单
 * （{@link io.agentmanager.framework.service.McpToolRegistrar#getForwardHeaderNames()}）——
 * <b>未声明不捕获</b>。链路：入口捕获（本类）→ RuntimeContext typed attribute 随行
 * （turn 级存活、SDK 不持久化）→ {@link McpUserContextMiddleware} 以
 * {@code reqHeader:<头名>} 合并进 McpMeta → {@link UserScopedMcpClientWrapper} 注入下游
 * HTTP header 并从 `_meta` 剥离。
 *
 * <p>安全约定（设计 §7）：凭据只存本对象（进程内、随 turn 丢弃）；不进日志（只记头名）、
 * 不进 Msg.metadata、不落任何表；平台 denylist 永不捕获（解析期剔除 + 捕获期二道防线）。
 */
public record ChatRequestHeaders(Map<String, String> headers) {

    private static final Logger log = LoggerFactory.getLogger(ChatRequestHeaders.class);

    /** McpMeta 保留命名空间前缀：middleware 以 reqHeader:<小写头名> 写入，wrapper 注入并剥离 */
    public static final String META_KEY_PREFIX = "reqHeader:";

    /** 空集单例：零声明服务零开销（capture 恒返回本实例） */
    public static final ChatRequestHeaders EMPTY = new ChatRequestHeaders(Map.of());

    /** 平台 denylist（小写精确匹配）：永不捕获/透传 */
    private static final Set<String> DENIED_HEADERS = Set.of(
        // hop-by-hop（RFC 9110 §7.6.1）
        "connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade",
        // 报文描述类头（对下游 MCP 无意义）+ 无前缀的零散项
        "host", "expect", "user-agent", "referer", "origin",
        // 平台自身会话凭据：转发即泄漏平台会话
        "cookie", "set-cookie",
        // 框架自用约定头（X-Forwarded-Prefix 前缀契约，subpath-routing-design §3.1）
        "x-forwarded-prefix");

    /**
     * 前缀 deny：代理链信息（X-Forwarded-*、Proxy-*）、报文描述类族（Content-*、Accept-*）
     * 与浏览器 fetch metadata（Sec-*）。CR #1：前缀匹配防枚举漏网
     * （proxy-authorization / content-disposition / accept-patch 等）。
     */
    private static final Set<String> DENIED_PREFIXES =
        Set.of("x-forwarded-", "sec-", "proxy-", "content-", "accept-");

    /** 捕获头数上限（声明数天然受限，此为纵深防御） */
    static final int MAX_HEADERS = 32;
    /** 单值长度上限（超限 drop + WARN，调用期 deny 以只含头名的错误浮出） */
    static final int MAX_VALUE_LENGTH = 4 * 1024;
    /** 捕获总字节上限（对齐 env 上限 32KB 量级） */
    static final int MAX_TOTAL_LENGTH = 32 * 1024;

    /** 该头名（应为小写）是否被平台 denylist 拒绝（解析期剔除与捕获期二道防线共用） */
    public static boolean isDenied(String lowercaseName) {
        if (lowercaseName == null || lowercaseName.isBlank()) {
            // 空名恒拒：解析期已剔除（CR #6），此处兜底防名单混入空串
            return true;
        }
        if (DENIED_HEADERS.contains(lowercaseName)) {
            return true;
        }
        for (var prefix : DENIED_PREFIXES) {
            if (lowercaseName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按声明名单捕获请求头。
     *
     * @param requestHeaders 原始请求头（Spring 注入；null 视作无头，单测直调兼容）
     * @param forwardNames   捕获名单（getForwardHeaderNames()；已 lowercase、已过解析期
     *                       denylist——此处再过一遍是二道防线，防名单缓存与策略演进不同步）
     */
    public static ChatRequestHeaders capture(HttpHeaders requestHeaders, Set<String> forwardNames) {
        if (requestHeaders == null || forwardNames == null || forwardNames.isEmpty()) {
            return EMPTY;
        }
        var captured = new LinkedHashMap<String, String>();
        int total = 0;
        for (var name : forwardNames) {
            var lower = name.toLowerCase();
            if (isDenied(lower)) {
                log.warn("[mcp-forward] declared header '{}' denied by platform policy, skipped", lower);
                continue;
            }
            var values = requestHeaders.get(lower);   // HttpHeaders 取值大小写不敏感
            if (values == null || values.isEmpty()) {
                // 请求未带：不在入口报错，调用期 on-missing 语义兜底（deny/passthrough）
                continue;
            }
            if (captured.size() >= MAX_HEADERS) {
                // CR #7：容量判断放在「请求确实携带」之后，避免前 32 个都未携带时提前截停+误报
                log.warn("[mcp-forward] capture cap {} reached, ignoring remaining names", MAX_HEADERS);
                break;
            }
            // RFC 9110 §5.2：多值 header 按逗号合并为单值
            var value = String.join(", ", values);
            int valueLength = value.length();
            if (valueLength > MAX_VALUE_LENGTH) {
                log.warn("[mcp-forward] header '{}' value {} chars exceeds cap {}, dropped",
                    lower, valueLength, MAX_VALUE_LENGTH);
                continue;
            }
            if (total + valueLength > MAX_TOTAL_LENGTH) {
                log.warn("[mcp-forward] capture total cap {} reached at '{}', ignoring remaining names",
                    MAX_TOTAL_LENGTH, lower);
                break;
            }
            total += valueLength;
            captured.put(lower, value);
        }
        return captured.isEmpty() ? EMPTY : new ChatRequestHeaders(Map.copyOf(captured));
    }

    /** 装入随行 RuntimeContext（typed attribute，仅本次调用可见、SDK 不持久化）；空集返回空 ctx */
    public RuntimeContext toRuntimeContext() {
        if (headers.isEmpty()) {
            return RuntimeContext.builder().build();
        }
        return RuntimeContext.builder().put(ChatRequestHeaders.class, this).build();
    }
}
