package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.TracingTestBase;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.opentelemetry.api.common.AttributeKey;
import reactor.core.publisher.Flux;

/**
 * ToolCallTracingMiddleware 测试：execute_tool span 上记录工具调用
 * 名称/描述/入参/出参、state、异常部分出参、无活跃 span 安全、事件透传。
 */
class ToolCallTracingMiddlewareTest extends TracingTestBase {

    private static final AttributeKey<String> INPUT_KEY =
        AttributeKey.stringKey("gen_ai.tool.call.arguments");
    private static final AttributeKey<String> OUTPUT_KEY =
        AttributeKey.stringKey("gen_ai.tool.call.result");

    private static ToolUseBlock toolCall(String id, String name, String argsJson) {
        return ToolUseBlock.builder().id(id).name(name).content(argsJson).build();
    }

    /** 在活跃 execute_tool span 内执行 onActing（模拟 OtelTracingMiddleware 已创建 span） */
    private void runInToolSpan(String spanName, ToolCallTracingMiddleware middleware,
                               ActingInput input,
                               Function<ActingInput, Flux<AgentEvent>> next) {
        var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test");
        var span = tracer.spanBuilder(spanName).startSpan();
        try (var scope = span.makeCurrent()) {
            middleware.onActing(mock(Agent.class), mock(RuntimeContext.class), input, next)
                .blockLast();
        } finally {
            span.end();
        }
    }

    /** 先走一次 onModelCall 缓存工具描述（ToolUseBlock 自身不携带描述） */
    private static void seedDescription(ToolCallTracingMiddleware middleware, String name,
                                        String description) {
        var schema = ToolSchema.builder().name(name).description(description).build();
        middleware.onModelCall(mock(Agent.class), mock(RuntimeContext.class),
            new ModelCallInput(List.of(), List.of(schema), null, mock(Model.class)),
            i -> Flux.empty()).blockLast();
    }

    @Test
    void shouldRecordToolCallInputAndOutputOnSpan() {
        var middleware = new ToolCallTracingMiddleware();
        seedDescription(middleware, "weather", "查询城市天气");

        var input = new ActingInput(List.of(toolCall("call-1", "weather", "{\"city\":\"beijing\"}")));

        runInToolSpan("execute_tool weather", middleware, input, i -> Flux.concat(
            Flux.just(
                new ToolResultTextDeltaEvent("r1", "call-1", "weather", "晴 "),
                new ToolResultTextDeltaEvent("r1", "call-1", "weather", "25℃")),
            Flux.just(new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS))));

        var spans = findSpans("execute_tool weather");
        assertEquals(1, spans.size());
        var attrs = spans.get(0).getAttributes();

        // 入参：id/name/description/arguments
        String in = attrs.get(INPUT_KEY);
        assertTrue(in.contains("call-1"));
        assertTrue(in.contains("weather"));
        assertTrue(in.contains("查询城市天气"));
        assertTrue(in.contains("city"));
        assertTrue(in.contains("beijing"));

        // 出参：文本增量拼接 + state（ToolResultState.getValue() 为小写）
        String out = attrs.get(OUTPUT_KEY);
        assertTrue(out.contains("晴 25℃"));
        assertTrue(out.contains("success"));
    }

    @Test
    void shouldRecordMultipleParallelToolCalls() {
        var middleware = new ToolCallTracingMiddleware();

        var input = new ActingInput(List.of(
            toolCall("call-1", "weather", "{\"city\":\"beijing\"}"),
            toolCall("call-2", "stock", "{\"code\":\"600000\"}")));

        runInToolSpan("execute_tool weather", middleware, input, i -> Flux.just(
            new ToolResultTextDeltaEvent("r1", "call-1", "weather", "晴"),
            new ToolResultTextDeltaEvent("r1", "call-2", "stock", "涨"),
            new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS),
            new ToolResultEndEvent("r1", "call-2", "stock", ToolResultState.ERROR)));

        String in = findSpans("execute_tool weather").get(0).getAttributes().get(INPUT_KEY);
        assertTrue(in.contains("call-1"));
        assertTrue(in.contains("call-2"));

        String out = findSpans("execute_tool weather").get(0).getAttributes().get(OUTPUT_KEY);
        assertTrue(out.contains("晴"));
        assertTrue(out.contains("涨"));
        assertTrue(out.contains("success"));
        assertTrue(out.contains("error"));
    }

    /** 数据块出参（ToolResultDataDeltaEvent）渲染进 output */
    @Test
    void shouldRenderDataBlockOutput() {
        var middleware = new ToolCallTracingMiddleware();
        var input = new ActingInput(List.of(toolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}")));

        runInToolSpan("execute_tool fs_read", middleware, input, i -> Flux.just(
            new ToolResultDataDeltaEvent("r1", "call-1", "fs_read",
                TextBlock.builder().text("file-content").build()),
            new ToolResultEndEvent("r1", "call-1", "fs_read", ToolResultState.SUCCESS)));

        String out = findSpans("execute_tool fs_read").get(0).getAttributes().get(OUTPUT_KEY);
        assertTrue(out.contains("file-content"));
    }

    /** 描述缓存未命中（工具未经 onModelCall 注册）时 description 为 null，不抛异常 */
    @Test
    void shouldTolerateMissingDescription() {
        var middleware = new ToolCallTracingMiddleware();
        var input = new ActingInput(List.of(toolCall("call-1", "unknown_tool", "{}")));

        runInToolSpan("execute_tool unknown_tool", middleware, input, i -> Flux.just(
            new ToolResultEndEvent("r1", "call-1", "unknown_tool", ToolResultState.SUCCESS)));

        String in = findSpans("execute_tool unknown_tool").get(0).getAttributes().get(INPUT_KEY);
        assertTrue(in.contains("unknown_tool"));
    }

    @Test
    void shouldTruncateOversizedOutput() {
        var middleware = new ToolCallTracingMiddleware();
        var input = new ActingInput(List.of(toolCall("call-1", "big", "{}")));
        var longText = "x".repeat(ModelIoTracingMiddleware.MAX_CONTENT_CHARS + 100);

        runInToolSpan("execute_tool big", middleware, input, i -> Flux.just(
            new ToolResultTextDeltaEvent("r1", "call-1", "big", longText),
            new ToolResultEndEvent("r1", "call-1", "big", ToolResultState.SUCCESS)));

        String out = findSpans("execute_tool big").get(0).getAttributes().get(OUTPUT_KEY);
        assertTrue(out.contains("(truncated, total"));
    }

    /** 异常中断时写入已累积的部分出参 */
    @Test
    void shouldWritePartialOutputOnError() {
        var middleware = new ToolCallTracingMiddleware();
        var input = new ActingInput(List.of(toolCall("call-1", "weather", "{}")));

        try {
            runInToolSpan("execute_tool weather", middleware, input, i -> Flux.concat(
                Flux.just(new ToolResultTextDeltaEvent("r1", "call-1", "weather", "partial")),
                Flux.error(new RuntimeException("tool boom"))));
        } catch (RuntimeException ignored) {
            // 预期异常传播
        }

        String out = findSpans("execute_tool weather").get(0).getAttributes().get(OUTPUT_KEY);
        assertTrue(out.contains("partial"));
    }

    @Test
    void shouldNotFailWithoutActiveSpan() {
        var middleware = new ToolCallTracingMiddleware();
        var input = new ActingInput(List.of(toolCall("call-1", "weather", "{}")));

        // 无活跃 span：Span.current() 为 invalid span，不抛异常
        var evt = middleware.onActing(mock(Agent.class), mock(RuntimeContext.class), input,
            i -> Flux.just(new ToolResultEndEvent("r1", "call-1", "weather",
                ToolResultState.SUCCESS))).blockLast();
        assertEquals("call-1", ((ToolResultEndEvent) evt).getToolCallId());
    }

    @Test
    void shouldPassEventsThroughUnchanged() {
        var middleware = new ToolCallTracingMiddleware();
        Function<ActingInput, Flux<AgentEvent>> next = i -> Flux.just(
            new ToolResultTextDeltaEvent("r1", "call-1", "weather", "x"),
            new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS),
            new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS));

        var result = middleware.onActing(mock(Agent.class), mock(RuntimeContext.class),
            new ActingInput(List.of()), next).collectList().block();
        assertEquals(3, result.size());
    }

    @Test
    void shouldRecordEmptyToolCallsAsEmptyArray() {
        var middleware = new ToolCallTracingMiddleware();

        runInToolSpan("execute_tool weather", middleware, new ActingInput(List.of()), i -> Flux.empty());

        String in = findSpans("execute_tool weather").get(0).getAttributes().get(INPUT_KEY);
        assertEquals("[]", in.trim());
    }

    @Test
    void shouldKeepArgumentsJsonInInput() {
        var middleware = new ToolCallTracingMiddleware();
        // content 非法 JSON 时回退 input map 序列化（resolveToolCallArgsJson 语义）
        var block = ToolUseBlock.builder().id("call-1").name("echo")
            .input(Map.of("msg", "hi")).build();
        var input = new ActingInput(List.of(block));

        runInToolSpan("execute_tool echo", middleware, input, i -> Flux.empty());

        String in = findSpans("execute_tool echo").get(0).getAttributes().get(INPUT_KEY);
        assertTrue(in.contains("msg"));
        assertTrue(in.contains("hi"));
    }
}
