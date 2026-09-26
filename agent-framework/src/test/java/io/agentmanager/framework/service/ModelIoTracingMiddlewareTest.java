package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.opentelemetry.api.common.AttributeKey;
import reactor.core.publisher.Flux;

/**
 * ModelIoTracingMiddleware 测试：chat span 上记录模型实际输入/输出、截断、
 * 异常/取消部分输出、无活跃 span 不抛异常、事件透传。
 */
class ModelIoTracingMiddlewareTest extends TracingTestBase {

    private static final AttributeKey<String> INPUT_KEY =
        AttributeKey.stringKey("gen_ai.input.messages");
    private static final AttributeKey<String> OUTPUT_KEY =
        AttributeKey.stringKey("gen_ai.output.messages");

    private static ModelCallInput inputWithMessages(List<Msg> messages) {
        return new ModelCallInput(messages, List.of(), null, mock(Model.class));
    }

    private static ModelCallEndEvent endEvent() {
        return new ModelCallEndEvent("r1", new ChatUsage(1, 1, 2, 0.1));
    }

    /** 在活跃 chat span 内执行中间件（模拟 OtelTracingMiddleware 已创建 span） */
    private void runInChatSpan(ModelIoTracingMiddleware middleware, ModelCallInput input,
                               Function<ModelCallInput, Flux<AgentEvent>> next) {
        var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test");
        var span = tracer.spanBuilder("chat gpt").startSpan();
        try (var scope = span.makeCurrent()) {
            middleware.onModelCall(mock(Agent.class), mock(RuntimeContext.class), input, next)
                .blockLast();
        } finally {
            span.end();
        }
    }

    @Test
    void shouldRecordInputAndOutputOnChatSpan() {
        var middleware = new ModelIoTracingMiddleware();

        var userMsg = Msg.builderForRole(MsgRole.USER).textContent("帮我查天气").build();
        var toolResultMsg = Msg.builderForRole(MsgRole.TOOL).textContent("晴 25℃").build();
        var assistantMsg = Msg.builderForRole(MsgRole.ASSISTANT)
            .content(ToolUseBlock.builder()
                .id("call-1").name("weather").content("{\"city\":\"beijing\"}").build())
            .build();

        var input = inputWithMessages(List.of(userMsg, assistantMsg, toolResultMsg));

        runInChatSpan(middleware, input, i -> Flux.concat(
            Flux.just(
                new ThinkingBlockDeltaEvent("r1", "think-1", "先看城市"),
                new TextBlockDeltaEvent("r1", "text-1", "北京今天"),
                new TextBlockDeltaEvent("r1", "text-1", "晴，25℃"),
                new ToolCallDeltaEvent("r1", "call-2", "weather", "{\"city\":")),
            Flux.concat(
                Flux.just(new ToolCallDeltaEvent("r1", "call-2", "weather", "\"beijing\"}")),
                Flux.just(endEvent()))));

        var spans = findSpans("chat gpt");
        assertEquals(1, spans.size());
        var attrs = spans.get(0).getAttributes();

        // 输入：role/content JSON，含工具调用与工具结果标注
        String in = attrs.get(INPUT_KEY);
        assertTrue(in.contains("\"role\":\"USER\"") || in.contains("\"role\": \"USER\""));
        assertTrue(in.contains("帮我查天气"));
        assertTrue(in.contains("[tool_call weather]"));
        assertTrue(in.contains("晴 25℃"));

        // 输出：正文增量拼接 + thinking + 工具调用参数增量拼接
        // （arguments 嵌入 JSON 后引号被转义，断言用不含引号的片段）
        String out = attrs.get(OUTPUT_KEY);
        assertTrue(out.contains("北京今天晴，25℃"));
        assertTrue(out.contains("先看城市"));
        assertTrue(out.contains("weather"));
        assertTrue(out.contains("city"));
        assertTrue(out.contains("beijing"));
    }

    @Test
    void shouldTruncateOversizedContent() {
        var middleware = new ModelIoTracingMiddleware();
        var longText = "a".repeat(ModelIoTracingMiddleware.MAX_CONTENT_CHARS + 100);
        var msg = Msg.builderForRole(MsgRole.USER).textContent(longText).build();

        runInChatSpan(middleware, inputWithMessages(List.of(msg)), i -> Flux.just(endEvent()));

        String in = findSpans("chat gpt").get(0).getAttributes().get(INPUT_KEY);
        assertTrue(in.contains("(truncated, total"));
        assertTrue(in.length() < ModelIoTracingMiddleware.MAX_CONTENT_CHARS + 100);
    }

    /** 异常中断时写入已累积的部分输出，便于排查 */
    @Test
    void shouldWritePartialOutputOnError() {
        var middleware = new ModelIoTracingMiddleware();
        var input = inputWithMessages(List.of(
            Msg.builderForRole(MsgRole.USER).textContent("hi").build()));

        try {
            runInChatSpan(middleware, input, i -> Flux.concat(
                Flux.just(new TextBlockDeltaEvent("r1", "t1", "partial")),
                Flux.error(new RuntimeException("llm down"))));
        } catch (RuntimeException ignored) {
            // 预期异常传播
        }

        String out = findSpans("chat gpt").get(0).getAttributes().get(OUTPUT_KEY);
        assertTrue(out.contains("partial"));
    }

    @Test
    void shouldNotFailWithoutActiveSpan() {
        var middleware = new ModelIoTracingMiddleware();
        var input = inputWithMessages(List.of(
            Msg.builderForRole(MsgRole.USER).textContent("hi").build()));

        // 无活跃 span：Span.current() 为 invalid span，不抛异常
        var evt = middleware.onModelCall(mock(Agent.class), mock(RuntimeContext.class), input,
            i -> Flux.just(new TextBlockDeltaEvent("r1", "t1", "x"), endEvent()))
            .blockLast();
        assertEquals("r1", ((ModelCallEndEvent) evt).getReplyId());
    }

    @Test
    void shouldPassEventsThroughUnchanged() {
        var middleware = new ModelIoTracingMiddleware();
        Function<ModelCallInput, Flux<AgentEvent>> next = i -> Flux.just(
            new TextBlockDeltaEvent("r1", "t1", "x"), endEvent(), endEvent());

        var result = middleware.onModelCall(mock(Agent.class), mock(RuntimeContext.class),
            inputWithMessages(List.of()), next).collectList().block();
        assertEquals(3, result.size());
        assertTrue(result.get(0) instanceof TextBlockDeltaEvent);
    }

    @Test
    void shouldRenderEmptyInputAsEmptyArray() {
        var middleware = new ModelIoTracingMiddleware();

        runInChatSpan(middleware, inputWithMessages(List.of()), i -> Flux.just(endEvent()));

        String in = findSpans("chat gpt").get(0).getAttributes().get(INPUT_KEY);
        assertEquals("[]", in.trim());
        String out = findSpans("chat gpt").get(0).getAttributes().get(OUTPUT_KEY);
        assertFalse(out.contains("tool_calls"));
    }
}
