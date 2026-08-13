package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.TracingTestBase;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import reactor.core.publisher.Flux;

/**
 * ReasoningTracingMiddleware 测试：span 创建、状态（OK/ERROR/cancel）、
 * 下游 active span 一致性（父子链前提）。
 */
class ReasoningTracingMiddlewareTest extends TracingTestBase {

    private static AgentEvent someEvent() {
        return new ModelCallEndEvent("r1", new ChatUsage(1, 1, 2, 0.1));
    }

    @Test
    void shouldCreateReasoningSpanAndActivateItDownstream() {
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        var ctx = mock(RuntimeContext.class);
        var input = new ReasoningInput(List.of(), List.of(), null);

        // 在信号回调（doOnNext）中捕获活跃 span：runWithContext 的 makeCurrent
        // 在信号投递时生效（订阅期/Flux.defer 内不生效，属 SDK 设计）
        var capturedSpanId = new AtomicReference<String>();
        Function<ReasoningInput, Flux<AgentEvent>> next = i -> Flux.just(someEvent())
                .doOnNext(e -> capturedSpanId.set(Span.current().getSpanContext().getSpanId()));

        new ReasoningTracingMiddleware().onReasoning(agent, ctx, input, next).blockLast();

        var spans = findSpans("reasoning assistant");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        // 下游捕获的 spanId 与完成的 reasoning span 一致 → 父子链成立的前提
        assertEquals(spans.get(0).getSpanId(), capturedSpanId.get());
    }

    @Test
    void shouldMarkErrorWhenStreamFails() {
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        var ctx = mock(RuntimeContext.class);
        var input = new ReasoningInput(List.of(), List.of(), null);

        Function<ReasoningInput, Flux<AgentEvent>> next =
                i -> Flux.error(new RuntimeException("llm timeout"));

        try {
            new ReasoningTracingMiddleware().onReasoning(agent, ctx, input, next).blockLast();
        } catch (RuntimeException ignored) {
            // 预期异常传播
        }

        var spans = findSpans("reasoning assistant");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        assertEquals(1, spans.get(0).getEvents().size());  // recordException
    }

    @Test
    void shouldEndSpanOnCancel() {
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        var ctx = mock(RuntimeContext.class);
        var input = new ReasoningInput(List.of(), List.of(), null);

        // 用永远不结束的流模拟取消
        Function<ReasoningInput, Flux<AgentEvent>> next =
                i -> Flux.<AgentEvent>never().doOnCancel(() -> { });

        var disposable = new ReasoningTracingMiddleware().onReasoning(agent, ctx, input, next)
                .subscribe();
        disposable.dispose();

        // 取消后 span 应已结束
        var spans = findSpans("reasoning assistant");
        assertEquals(1, spans.size());
    }

    @Test
    void shouldSetMessagesCountAttribute() {
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        var ctx = mock(RuntimeContext.class);
        // 2 条消息的 input
        var input = new ReasoningInput(List.of(mock(io.agentscope.core.message.Msg.class),
                mock(io.agentscope.core.message.Msg.class)), List.of(), null);

        new ReasoningTracingMiddleware().onReasoning(agent, ctx, input,
                i -> Flux.just(someEvent())).blockLast();

        var spans = findSpans("reasoning assistant");
        assertEquals(1, spans.size());
        assertEquals(2L, spans.get(0).getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.request.messages.count")));
    }

    @Test
    void shouldReportEmptyMessagesCountAsZero() {
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        var ctx = mock(RuntimeContext.class);
        var input = new ReasoningInput(null, List.of(), null);

        new ReasoningTracingMiddleware().onReasoning(agent, ctx, input,
                i -> Flux.just(someEvent())).blockLast();

        var spans = findSpans("reasoning assistant");
        assertEquals(0L, spans.get(0).getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.longKey("gen_ai.request.messages.count")));
        assertTrue(spans.get(0).getStatus().getStatusCode() == StatusCode.OK);
    }
}