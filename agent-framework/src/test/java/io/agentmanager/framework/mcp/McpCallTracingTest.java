package io.agentmanager.framework.mcp;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.TracingTestBase;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 出站调用链路追踪单测（chat-trace-observability-design §1）。
 *
 * <p>基类注册 InMemorySpanExporter 的 OTel SDK（GlobalOpenTelemetry 全局单例）；
 * 父 span 在订阅时 makeCurrent，模拟 execute_tool span 作用域内的出站调用。
 */
class McpCallTracingTest extends TracingTestBase {

    private static final McpSchema.CallToolResult OK_RESULT =
        McpSchema.CallToolResult.builder().addTextContent("ok").build();

    @Test
    void createsChildSpanWithMcpAttributesUnderActiveParent() {
        var tracer = GlobalOpenTelemetry.getTracer("test");
        Span parent = tracer.spanBuilder("execute_tool").startSpan();
        try (var scope = parent.makeCurrent()) {
            Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> mono =
                McpCallTracing.callTool("weather", "get_weather",
                    Map.of("city", "杭州"), Map.of("userId", "u-1"),
                    Set.of("X-User-Id"),
                    () -> Mono.just(OK_RESULT));
            mono.block();
        } finally {
            parent.end();
        }

        var spans = findSpans("mcp.call_tool");
        assertEquals(1, spans.size(), "应产出恰好一个 mcp.call_tool span: " + spans.size());
        var span = spans.get(0);
        assertEquals(parent.getSpanContext().getTraceId(),
            span.getSpanContext().getTraceId(), "应为父 span（execute_tool）的同 trace 子 span");
        assertEquals("weather", span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("mcp.server.name")));
        assertEquals("get_weather", span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("mcp.tool.name")));
        assertTrue(String.valueOf(span.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("mcp.call.arguments")))
            .contains("杭州"), "最终出站入参应记录: " + span.getAttributes());
        // header 只记 key 名、绝不记值（安全约束 §1.2）
        assertEquals("X-User-Id", span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("mcp.call.header_names")));
        assertFalse(span.getAttributes().toString().contains("Bearer"),
            "header 值不得进入 span 属性");
        assertEquals("userId", span.getAttributes().get(
            io.opentelemetry.api.common.AttributeKey.stringKey("mcp.call.meta_keys")));
    }

    @Test
    void marksErrorStatusAndRecordsException() {
        var tracer = GlobalOpenTelemetry.getTracer("test");
        Span parent = tracer.spanBuilder("execute_tool").startSpan();
        try (var scope = parent.makeCurrent()) {
            McpCallTracing.callTool("weather", "boom", Map.of(), null, Set.of(),
                () -> Mono.error(new IllegalStateException("downstream 500")))
                .onErrorResume(e -> Mono.just(OK_RESULT))
                .block();
        } finally {
            parent.end();
        }

        var spans = findSpans("mcp.call_tool");
        assertEquals(1, spans.size());
        var span = spans.get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode(),
            "出站失败 span 应为 ERROR 状态");
        assertTrue(span.getEvents().stream().anyMatch(e -> e.getName().contains("exception")),
            "异常应入 span 事件（下游报错定位证据）");
    }

    @Test
    void noParentSpanPassesThroughWithoutCreatingOrphans() {
        // 父 span 无效（context 断链）：不建 span 直接放行——绝不产生孤儿 trace（§1.3）
        var result = McpCallTracing.callTool("weather", "get_weather", Map.of(), null, Set.of(),
            () -> Mono.just(OK_RESULT)).block();
        assertNotNull(result);
        assertTrue(findSpans("mcp.call_tool").isEmpty(),
            "无有效父 span 时不得建 span");
    }
}
