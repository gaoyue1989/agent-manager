package io.agentmanager.framework.mcp;

import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import reactor.core.publisher.Mono;

/**
 * MCP 出站调用链路追踪（chat-trace-observability-design §1）。
 *
 * <p><b>为什么需要</b>：SDK 的 {@code execute_tool} span 只能覆盖到模型侧工具入参/出参
 * （{@code ToolCallTracingMiddleware} 补录的 {@code gen_ai.tool.call.arguments/result}），
 * 而 MCP 工具的**出站边界**发生在 {@link UserScopedMcpClientWrapper#callTool} 内部——
 * 调到了哪个 server、{@code arguments} 通道注入后真正发到下游的最终参数、注入了哪些
 * header，中间件层都不可见。排查 MCP 业务工具问题时，这些正是最需要的信息。
 *
 * <p><b>span 形态</b>：在 {@code execute_tool} 之下建子 span {@code mcp.call_tool}
 * （trace 树形：{@code HTTP → invoke_agent → … → execute_tool → mcp.call_tool}），
 * 属性词表：
 * <ul>
 *   <li>{@code mcp.server.name}：MCP server 名；</li>
 *   <li>{@code mcp.tool.name}：出站工具名；</li>
 *   <li>{@code mcp.call.arguments}：注入 merge 后的**最终出站入参** JSON（8192 截断，
 *       与 {@code ModelIoTracingMiddleware} 同口径）；</li>
 *   <li>{@code mcp.call.header_names}：本次注入 header 的 **key 名清单**（逗号连接）——
 *       只记名、永不记值（header 值是凭据/透传头，与既有「错误只含 key 名」同一安全口径）；</li>
 *   <li>{@code mcp.call.meta_keys}：出站 {@code _meta} 的 key 名清单（{@code reqHeader:*}
 *       已剥离后）——同理只记名。</li>
 * </ul>
 *
 * <p><b>fail-soft 与零开销边界</b>：
 * <ul>
 *   <li>经 {@code GlobalOpenTelemetry} 取 tracer：OTel 关闭（{@code OTEL_TRACES_EXPORTER=none}
 *       默认值）时为 no-op tracer，{@code isRecording()} 为 false → 跳过 arguments JSON
 *       序列化，热路径零成本；</li>
 *   <li>父 span 无效（Reactor context 断链的极端场景）时**不建 span 直接放行**——绝不产生
 *       孤儿 trace（与 {@code ToolCallTracingMiddleware}「无有效 span 不做任何序列化」
 *       同款守卫）；</li>
 *   <li>属性写入/序列化任何异常吞掉记 DEBUG，不影响调用链。</li>
 * </ul>
 */
public final class McpCallTracing {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 惰性获取：构造早于 OtelConfig 注册 SDK 时会拿到永久 no-op tracer（HttpTracingFilter 同款教训） */
    private static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer("io.agentmanager.mcp");
    }

    private McpCallTracing() {
    }

    /**
     * 包裹一次 MCP 工具出站调用：订阅时建 span、成功/异常/取消时收 span（耗时由 span
     * 自带 duration 承载，Jaeger/Langfuse 直接可读）。
     *
     * @param serverName       MCP server 名（{@code McpClientWrapper.getName()}）
     * @param toolName         出站工具名
     * @param outboundArguments 注入 merge 后的最终出站入参（原样引用，不做拷贝）
     * @param outboundMeta      出站 {@code _meta}（{@code reqHeader:*} 已剥离；可为 null）
     * @param headerNames       本次注入 header 的 key 集合（只记名；空集 = 走静态凭据）
     * @param call              实际出站调用（defer 内调用，保证 span 覆盖真实订阅区间）
     */
    public static Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> callTool(
            String serverName, String toolName, Map<String, Object> outboundArguments,
            Map<String, Object> outboundMeta, java.util.Set<String> headerNames,
            Supplier<Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult>> call) {
        return Mono.defer(() -> {
            // 父 span 无效：不建 span 直接放行（绝不产生孤儿 trace，§1.3）
            Span parent = Span.current();
            if (parent == null || !parent.getSpanContext().isValid()) {
                return call.get();
            }
            Span span = tracer().spanBuilder("mcp.call_tool")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .startSpan();
            try {
                span.setAttribute("mcp.server.name", String.valueOf(serverName));
                span.setAttribute("mcp.tool.name", String.valueOf(toolName));
                // arguments JSON 序列化在 isRecording() 通过后才做：no-op span 热路径零成本
                if (span.isRecording()) {
                    span.setAttribute("mcp.call.arguments",
                        io.agentmanager.framework.util.TraceText.truncate(toJson(outboundArguments)));
                    if (headerNames != null && !headerNames.isEmpty()) {
                        span.setAttribute("mcp.call.header_names", String.join(",", headerNames));
                    }
                    if (outboundMeta != null && !outboundMeta.isEmpty()) {
                        span.setAttribute("mcp.call.meta_keys", String.join(",", outboundMeta.keySet()));
                    }
                }
            } catch (Exception e) {
                // 属性写入失败不阻断调用链（fail-soft）
                span.setAttribute("mcp.call.attributes_error", String.valueOf(e.getMessage()));
            }
            return call.get()
                .doOnSuccess(v -> endOk(span))
                .doOnError(e -> endError(span, e))
                .doOnCancel(() -> endOk(span));
        });
    }

    /** 成功/取消收 span（取消不算错误：调用方主动放弃，下游副作用不可撤） */
    private static void endOk(Span span) {
        span.end();
    }

    /** 异常收 span：ERROR 状态 + 异常入 span（下游报错定位的直接证据） */
    private static void endError(Span span, Throwable e) {
        span.setStatus(StatusCode.ERROR, e.getMessage() != null
            ? e.getMessage() : e.getClass().getSimpleName());
        span.recordException(e);
        span.end();
    }

    /** Map → JSON（fail-soft：序列化失败降级 toString，内部仅简单 Map/List，不应失败） */
    private static String toJson(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
