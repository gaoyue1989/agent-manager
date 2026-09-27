package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.TracingTestBase;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

/**
 * FrameworkTracingMiddleware 测试：活跃 span 下写入业务属性，事件透传。
 */
class FrameworkTracingMiddlewareTest extends TracingTestBase {

    private static AgentEvent someEvent() {
        return new ModelCallEndEvent("r1", new ChatUsage(1, 1, 2, 0.1));
    }

    @Test
    void shouldEnrichCurrentSpanDuringAgentCall() {
        var middleware = new FrameworkTracingMiddleware("acme");
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);
        when(ctx.getUserId()).thenReturn("user-7");
        when(ctx.getSessionId()).thenReturn("acme:thread-1");

        // 用 SDK tracer 显式创建活跃 span（模拟 OtelTracingMiddleware 已创建 span）
        var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test");
        var parent = tracer.spanBuilder("invoke_agent test-agent").startSpan();
        try (var scope = parent.makeCurrent()) {
            middleware.onAgent(agent, ctx, new AgentInput(List.of()),
                    i -> Flux.just(someEvent())).blockLast();
        } finally {
            parent.end();
        }

        var spans = findSpans("invoke_agent test-agent");
        assertEquals(1, spans.size());
        var attrs = spans.get(0).getAttributes();
        assertEquals("user-7", attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("agentscope.user.id")));
        assertEquals("acme:thread-1", attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("agentscope.session.id")));
        assertEquals("acme", attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("agentscope.tenant.prefix")));
    }

    @Test
    void shouldSkipBlankAttributes() {
        var middleware = new FrameworkTracingMiddleware("");
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);
        when(ctx.getUserId()).thenReturn("");
        when(ctx.getSessionId()).thenReturn(null);

        var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test");
        var parent = tracer.spanBuilder("invoke_agent test-agent").startSpan();
        try (var scope = parent.makeCurrent()) {
            middleware.onModelCall(agent, ctx,
                    new ModelCallInput(List.of(), List.of(), null, mock(Model.class)),
                    i -> Flux.just(someEvent())).blockLast();
        } finally {
            parent.end();
        }

        var spans = findSpans("invoke_agent test-agent");
        var attrs = spans.get(0).getAttributes();
        assertNull(attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("agentscope.user.id")));
        assertTrue(attrs.isEmpty());
    }

    @Test
    void shouldNotFailWithoutActiveSpan() {
        var middleware = new FrameworkTracingMiddleware("acme");
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);

        // 无活跃 span：Span.current() 为 invalid span，不应抛异常
        var events = middleware.onActing(agent, ctx,
                new io.agentscope.core.middleware.ActingInput(List.of()),
                i -> Flux.just(someEvent())).blockLast();
        assertEquals("r1", ((ModelCallEndEvent) events).getReplyId());
    }

    @Test
    void shouldRecordOnModelCallWhenActiveSpanIsChatSpan() {
        var middleware = new FrameworkTracingMiddleware("acme");
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);
        when(ctx.getUserId()).thenReturn("user-9");

        var msg = mock(Msg.class);
        when(msg.getRole()).thenReturn(MsgRole.USER);
        when(msg.getContent()).thenReturn(List.of(TextBlock.builder().build()));
        var tool = mock(ToolSchema.class);
        when(tool.getName()).thenReturn("echo");

        var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test");
        var parent = tracer.spanBuilder("chat gpt").startSpan();
        try (var scope = parent.makeCurrent()) {
            middleware.onModelCall(agent, ctx,
                    new ModelCallInput(List.of(msg), List.of(tool), null, mock(Model.class)),
                    i -> Flux.just(someEvent())).blockLast();
        } finally {
            parent.end();
        }

        var spans = findSpans("chat gpt");
        assertEquals(1, spans.size());
        assertEquals("user-9", spans.get(0).getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("agentscope.user.id")));
    }

    @Test
    void shouldPassEventsThroughUnchanged() {
        var middleware = new FrameworkTracingMiddleware("acme");
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);
        when(ctx.getUserId()).thenReturn("u");

        Function<AgentInput, Flux<AgentEvent>> next = i -> Flux.just(someEvent(), someEvent());

        var result = middleware.onAgent(agent, ctx, new AgentInput(List.of()), next)
                .collectList().block();
        assertEquals(2, result.size());
        // doOnNext 仅副作用，不改变事件流
        assertEquals("r1", ((ModelCallEndEvent) result.get(0)).getReplyId());
    }

    // ===== issue #44 同源缺陷：span 属性必须是业务规范键，不是 RuntimeContext 原值 =====

    /**
     * Channel 链路形态：ctx.sessionId = 网关共享 gw-hash、ctx.userId = peer（前端 sid）。
     * 记原值会让 agentscope.session.id 全进程同值、agentscope.user.id 变成会话 id，
     * 按会话/用户过滤 trace 失效且各会话 span 互相可见。
     */
    @Test
    void shouldRecordCanonicalSessionAndRealUserOnChannelLink() {
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("gw-3f20f08c5499")).thenReturn(null);
        when(store.findUserIdBySession("peer-sid-1")).thenReturn("alice");
        var middleware = new FrameworkTracingMiddleware("acme", new SessionKeyResolver(store));

        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("gw-3f20f08c5499");
        when(ctx.getUserId()).thenReturn("peer-sid-1");

        var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test");
        var span = tracer.spanBuilder("invoke_agent test-agent").startSpan();
        try (var scope = span.makeCurrent()) {
            middleware.onAgent(mock(Agent.class), ctx, new AgentInput(List.of()),
                i -> Flux.just(someEvent(), someEvent())).blockLast();
        } finally {
            span.end();
        }

        var attrs = findSpans("invoke_agent test-agent").get(0).getAttributes();
        assertEquals("peer-sid-1",
            attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("agentscope.session.id")));
        assertEquals("alice",
            attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("agentscope.user.id")));
    }

    /** A2A 链路：ctx.sessionId 已是调用方 sid、ctx.userId 已是真实用户，两个属性原样保留 */
    @Test
    void shouldKeepContextValuesOnA2ALink() {
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("a2a-sid-1")).thenReturn("caller");
        var middleware = new FrameworkTracingMiddleware("acme", new SessionKeyResolver(store));

        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("a2a-sid-1");
        when(ctx.getUserId()).thenReturn("real-user");

        var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test");
        var span = tracer.spanBuilder("invoke_agent test-agent").startSpan();
        try (var scope = span.makeCurrent()) {
            middleware.onAgent(mock(Agent.class), ctx, new AgentInput(List.of()),
                i -> Flux.just(someEvent())).blockLast();
        } finally {
            span.end();
        }

        var attrs = findSpans("invoke_agent test-agent").get(0).getAttributes();
        assertEquals("a2a-sid-1",
            attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("agentscope.session.id")));
        assertEquals("caller",
            attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey("agentscope.user.id")));
    }

    /**
     * 热路径约束：解析结果 memo 在 RuntimeContext（生命周期 = 一个 turn），
     * 多 hook / 多事件不得反复查库——事件级查库会把 span 采集变成 DB 密集型负载。
     */
    @Test
    void shouldResolveOncePerRuntimeContext() {
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("gw-3f20f08c5499")).thenReturn(null);
        when(store.findUserIdBySession("peer-sid-2")).thenReturn("bob");
        var middleware = new FrameworkTracingMiddleware("acme", new SessionKeyResolver(store));

        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("gw-3f20f08c5499");
        when(ctx.getUserId()).thenReturn("peer-sid-2");

        // 用 map 承载 ctx 的 memo 语义（真实 RuntimeContext 也按 key 存）
        var memoStore = new java.util.concurrent.ConcurrentHashMap<Class<?>, Object>();
        when(ctx.get(any(Class.class))).thenAnswer(inv -> memoStore.get(inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            memoStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).put(any(Class.class), any());

        var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test");
        var span = tracer.spanBuilder("invoke_agent test-agent").startSpan();
        try (var scope = span.makeCurrent()) {
            middleware.onAgent(mock(Agent.class), ctx, new AgentInput(List.of()),
                    i -> Flux.just(someEvent(), someEvent(), someEvent())).blockLast();
            middleware.onModelCall(mock(Agent.class), ctx,
                    new ModelCallInput(List.of(), List.of(), null, mock(Model.class)),
                    i -> Flux.just(someEvent())).blockLast();
        } finally {
            span.end();
        }

        // 4 个事件 + 2 个 hook，DB 反查仍只发生 2 次（每个候选各一次），后续全走 memo
        verify(store, times(1)).findUserIdBySession("gw-3f20f08c5499");
        verify(store, times(1)).findUserIdBySession("peer-sid-2");
    }
}