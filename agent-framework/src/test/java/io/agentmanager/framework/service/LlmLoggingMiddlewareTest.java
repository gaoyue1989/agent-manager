package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

class LlmLoggingMiddlewareTest {

    @Test
    void shouldLogModelCallOnEndEvent() {
        var logger = new LLMLogger();
        var middleware = new LlmLoggingMiddleware(logger);
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("acme-test-agent__thread-1");

        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("openrouter/free");

        var msg = mock(Msg.class);
        when(msg.getRole()).thenReturn(MsgRole.USER);
        when(msg.getContent()).thenReturn(List.of(TextBlock.builder().build()));

        var tool = mock(ToolSchema.class);
        when(tool.getName()).thenReturn("echo");

        var input = new ModelCallInput(List.of(msg), List.of(tool), null, model);
        var endEvent = new ModelCallEndEvent("reply-1", new ChatUsage(10, 20, 30, 1.5));

        Function<ModelCallInput, Flux<AgentEvent>> next = (i) -> Flux.just(endEvent);

        middleware.onModelCall(agent, ctx, input, next).blockLast();

        var calls = logger.getCalls("acme-test-agent__thread-1");
        assertEquals(1, calls.size());
        var call = calls.get(0);
        assertNotNull(call.request());
        assertEquals("openrouter/free", call.request().get("model"));
        assertEquals(1, ((List<?>) call.request().get("tools")).size());
        @SuppressWarnings("unchecked")
        var usage = (Map<String, Object>) call.response().get("usage");
        assertEquals(30, usage.get("total_tokens"));
        assertNotNull(call.response().get("duration_ms"));
    }

    @Test
    void shouldFallbackToUserIdWhenSessionMissing() {
        var logger = new LLMLogger();
        var middleware = new LlmLoggingMiddleware(logger);
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("");
        when(ctx.getUserId()).thenReturn("user-9");

        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("m");
        var input = new ModelCallInput(List.of(), List.of(), null, model);

        middleware.onModelCall(agent, ctx, input,
            (i) -> Flux.just(new ModelCallEndEvent("r", new ChatUsage(1, 1, 2, 0.1))))
            .blockLast();

        assertEquals(1, logger.getCalls("user-9").size());
    }

    /** 回归锁：max-iterations 总结等裸模型调用 tools=null，曾 NPE 导致 "Error generating summary" */
    @Test
    void shouldTolerateNullTools() {
        var logger = new LLMLogger();
        var middleware = new LlmLoggingMiddleware(logger);
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("acme-test-agent:thread-1");

        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("m");
        var input = new ModelCallInput(List.of(), null, null, model);

        middleware.onModelCall(agent, ctx, input,
            (i) -> Flux.just(new ModelCallEndEvent("r", new ChatUsage(1, 1, 2, 0.1))))
            .blockLast();

        var calls = logger.getCalls("acme-test-agent:thread-1");
        assertEquals(1, calls.size());
        assertEquals(0, ((List<?>) calls.get(0).request().get("tools")).size());
    }

    /** 回归锁：usage 缺失（部分推理端点不回报）时 token 记 0，不得 NPE */
    @Test
    void shouldTolerateNullUsage() {
        var logger = new LLMLogger();
        var middleware = new LlmLoggingMiddleware(logger);
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("acme-test-agent:thread-1");

        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("m");
        var input = new ModelCallInput(List.of(), List.of(), null, model);

        middleware.onModelCall(agent, ctx, input,
            (i) -> Flux.just(new ModelCallEndEvent("r", null)))
            .blockLast();

        var calls = logger.getCalls("acme-test-agent:thread-1");
        assertEquals(1, calls.size());
        @SuppressWarnings("unchecked")
        var usage = (Map<String, Object>) calls.get(0).response().get("usage");
        assertEquals(0, usage.get("input_tokens"));
        assertEquals(0, usage.get("output_tokens"));
        assertEquals(0, usage.get("total_tokens"));
    }

    /** sessionId 与 userId 均缺失时回退 "global" 汇聚键 */
    @Test
    void shouldFallbackToGlobalWhenSessionAndUserMissing() {
        var logger = new LLMLogger();
        var middleware = new LlmLoggingMiddleware(logger);
        var agent = mock(Agent.class);
        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("");
        when(ctx.getUserId()).thenReturn("");

        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("m");
        var input = new ModelCallInput(List.of(), List.of(), null, model);

        middleware.onModelCall(agent, ctx, input,
            (i) -> Flux.just(new ModelCallEndEvent("r", new ChatUsage(1, 1, 2, 0.1))))
            .blockLast();

        assertEquals(1, logger.getCalls("global").size());
    }

    // ===== issue #44：记录键必须与 /threads/{sid}/llm-calls 的查询键一致 =====

    /**
     * Channel 链路（/threads/chat 经 ChatUiChannel 网关）：sessionId = 网关按 canonicalKey
     * 派生的 gw-hash（同进程所有 peer 共享），前端 sid 落在 userId。
     * 记录必须写到 userId，否则按 sid 查询恒空、且各会话记录互相串。
     */
    @Test
    void shouldLogUnderPeerWhenSessionIdIsSharedGatewayHash() {
        var logger = new LLMLogger();
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("gw-3f20f08c5499")).thenReturn(null);
        when(store.findUserIdBySession("debug-user_sid-1")).thenReturn("debug-user");
        var middleware = new LlmLoggingMiddleware(logger, new SessionKeyResolver(store));

        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("gw-3f20f08c5499");
        when(ctx.getUserId()).thenReturn("debug-user_sid-1");

        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("m");
        var input = new ModelCallInput(List.of(), List.of(), null, model);

        middleware.onModelCall(mock(Agent.class), ctx, input,
            (i) -> Flux.just(new ModelCallEndEvent("r", new ChatUsage(1, 2, 3, 0.1))))
            .blockLast();

        assertEquals(1, logger.getCalls("debug-user_sid-1").size());
        // 共享 gw-hash 桶必须为空：否则所有会话的 prompt 串到一处
        assertEquals(0, logger.getCalls("gw-3f20f08c5499").size());
    }

    /** A2A 链路：sessionId 已是调用方 sid（规范），即便 userId 也登记过也优先用 sessionId */
    @Test
    void shouldPreferSessionIdOnA2ALink() {
        var logger = new LLMLogger();
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("a2a-sid-1")).thenReturn("caller");
        var middleware = new LlmLoggingMiddleware(logger, new SessionKeyResolver(store));

        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("a2a-sid-1");
        when(ctx.getUserId()).thenReturn("real-user");

        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("m");
        var input = new ModelCallInput(List.of(), List.of(), null, model);

        middleware.onModelCall(mock(Agent.class), ctx, input,
            (i) -> Flux.just(new ModelCallEndEvent("r", new ChatUsage(1, 2, 3, 0.1))))
            .blockLast();

        assertEquals(1, logger.getCalls("a2a-sid-1").size());
        assertEquals(0, logger.getCalls("real-user").size());
    }

    /** 两个候选都未登记（如 direct invoke 的 {tenant}__{tid}）：沿用 sessionId，读取侧前缀归回退仍能命中 */
    @Test
    void shouldKeepSessionIdWhenNeitherCandidateRegistered() {
        var logger = new LLMLogger();
        var store = mock(SessionUserStore.class);
        var middleware = new LlmLoggingMiddleware(logger, new SessionKeyResolver(store));

        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("acme-test-agent__thread-1");
        when(ctx.getUserId()).thenReturn("vendor-key");

        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("m");
        var input = new ModelCallInput(List.of(), List.of(), null, model);

        middleware.onModelCall(mock(Agent.class), ctx, input,
            (i) -> Flux.just(new ModelCallEndEvent("r", new ChatUsage(1, 2, 3, 0.1))))
            .blockLast();

        assertEquals(1, logger.getCalls("acme-test-agent__thread-1").size());
    }

    /** 反查抛异常（DB 抖动）时 fail-soft 回落 sessionId，不得因记录可观测性丢一次模型调用 */
    @Test
    void shouldNotBreakWhenLookupThrows() {
        var logger = new LLMLogger();
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("gw-3f20f08c5499")).thenThrow(new RuntimeException("db down"));
        when(store.findUserIdBySession("debug-user_sid-2")).thenThrow(new RuntimeException("db down"));
        var middleware = new LlmLoggingMiddleware(logger, new SessionKeyResolver(store));

        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("gw-3f20f08c5499");
        when(ctx.getUserId()).thenReturn("debug-user_sid-2");

        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("m");
        var input = new ModelCallInput(List.of(), List.of(), null, model);

        middleware.onModelCall(mock(Agent.class), ctx, input,
            (i) -> Flux.just(new ModelCallEndEvent("r", new ChatUsage(1, 2, 3, 0.1))))
            .blockLast();

        assertEquals(1, logger.getCalls("gw-3f20f08c5499").size());
    }
}
