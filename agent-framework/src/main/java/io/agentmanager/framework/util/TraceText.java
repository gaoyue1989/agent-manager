package io.agentmanager.framework.util;

/**
 * 链路追踪属性文本工具（chat-trace-observability-design §1.3）。
 *
 * <p>span 属性的超长截断原先内嵌在 {@code ModelIoTracingMiddleware}（service 包内，
 * 包级可见），MCP 出站调用追踪（{@code UserScopedMcpClientWrapper}，mcp 包）需要同一
 * 截断口径而不可达——提取到 util 供两侧共享，保证「截断长度 + 标注格式」跨中间件一致。
 */
public final class TraceText {

    private TraceText() {
    }

    /** span 内容属性的单条上限（字符）：与 ModelIoTracingMiddleware.MAX_CONTENT_CHARS 同值 */
    public static final int MAX_CONTENT_CHARS = 8192;

    /**
     * 超长截断：保留前缀并标注原始总长，超出上限的尾巴对排查无价值、纯占 span 属性体积
     * （OTel 属性有大小上限，超限整条会被丢弃——截断是保住可读前缀的手段）。
     */
    public static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= MAX_CONTENT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_CONTENT_CHARS)
            + "...(truncated, total " + s.length() + " chars)";
    }
}
