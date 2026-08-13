package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.TracingTestBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import reactor.core.publisher.Flux;

/**
 * TracingModelWrapper 测试：stream 创建 span、usage 属性捕获、租户属性复制、委托。
 */
class TracingModelWrapperTest extends TracingTestBase {

    private static ChatResponse responseWithUsage() {
        return new ChatResponse("reply-1", List.of(), new ChatUsage(10, 20, 30, 0.5), null, "stop");
    }

    private static Model mockModel() {
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("gpt-test");
        return model;
    }

    @Test
    void shouldCreateSpanAndCaptureUsage() {
        var model = mockModel();
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(responseWithUsage()));

        var wrapper = new TracingModelWrapper(model, "memory");
        wrapper.stream(List.of(), List.of(), null).blockLast();

        var spans = findSpans("memory");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        assertEquals("memory", spans.get(0).getAttributes()
                .get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals("gpt-test", spans.get(0).getAttributes()
                .get(AttributeKey.stringKey("gen_ai.request.model")));
        assertEquals(10L, spans.get(0).getAttributes()
                .get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(20L, spans.get(0).getAttributes()
                .get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(0L, spans.get(0).getAttributes()
                .get(AttributeKey.longKey("gen_ai.request.messages.count")));
    }

    @Test
    void shouldUseCompactionSpanName() {
        var model = mockModel();
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(responseWithUsage()));

        var wrapper = new TracingModelWrapper(model, "compaction");
        wrapper.stream(List.of(), List.of(), null).blockLast();

        assertEquals(1, findSpans("compaction").size());
        assertEquals(0, findSpans("memory").size());
    }

    @Test
    void shouldCopyTenantAttributesFromActiveParent() {
        var model = mockModel();
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(responseWithUsage()));

        var parent = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test")
                .spanBuilder("invoke_agent test")
                .setAttribute("agentscope.user.id", "user-1")
                .setAttribute("agentscope.session.id", "acme:t1")
                .setAttribute("agentscope.tenant.prefix", "acme")
                .startSpan();
        try (var scope = parent.makeCurrent()) {
            new TracingModelWrapper(model, "memory").stream(List.of(), List.of(), null).blockLast();
        } finally {
            parent.end();
        }

        var child = findSpans("memory").get(0);
        assertEquals("user-1", child.getAttributes().get(AttributeKey.stringKey("agentscope.user.id")));
        assertEquals("acme:t1", child.getAttributes().get(AttributeKey.stringKey("agentscope.session.id")));
        assertEquals("acme", child.getAttributes().get(AttributeKey.stringKey("agentscope.tenant.prefix")));
        // 父子关系成立
        assertEquals(parent.getSpanContext().getSpanId(), child.getParentSpanId());
        assertEquals(parent.getSpanContext().getTraceId(), child.getTraceId());
    }

    @Test
    void shouldNotFailWhenParentSpanIsNotReadable() {
        // 无活跃 span（no-op）：Context.current() 无 span，跳过属性复制，不抛异常
        var model = mockModel();
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(responseWithUsage()));

        new TracingModelWrapper(model, "memory").stream(List.of(), List.of(), null).blockLast();

        assertEquals(1, findSpans("memory").size());
    }

    @Test
    void shouldDelegateAllModelMethods() {
        var model = mockModel();
        when(model.supportsNativeStructuredOutput()).thenReturn(true);
        when(model.supportsNativeStructuredOutputWithTools()).thenReturn(false);
        when(model.getContextWindowSize()).thenReturn(8192);

        var wrapper = new TracingModelWrapper(model, "memory");
        assertEquals("gpt-test", wrapper.getModelName());
        assertEquals(true, wrapper.supportsNativeStructuredOutput());
        assertEquals(false, wrapper.supportsNativeStructuredOutputWithTools());
        assertEquals(8192, wrapper.getContextWindowSize());
        verify(model).getModelName();
    }

    @Test
    void shouldMarkErrorWhenStreamFails() {
        var model = mockModel();
        when(model.stream(any(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("llm down")));

        var wrapper = new TracingModelWrapper(model, "memory");
        try {
            wrapper.stream(List.of(), List.of(), null).blockLast();
        } catch (RuntimeException ignored) {
            // 预期异常传播
        }

        var spans = findSpans("memory");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        assertEquals(1, spans.get(0).getEvents().size());
    }
}