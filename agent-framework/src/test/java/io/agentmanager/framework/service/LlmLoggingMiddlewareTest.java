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
        when(ctx.getSessionId()).thenReturn("acme-test-agent:thread-1");

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

        var calls = logger.getCalls("acme-test-agent:thread-1");
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
}
