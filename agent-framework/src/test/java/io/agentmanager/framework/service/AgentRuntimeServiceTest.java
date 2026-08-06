package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class AgentRuntimeServiceTest {

    private HarnessAgent agent;
    private AgentRuntimeService service;

    @BeforeEach
    void setUp() {
        agent = mock(HarnessAgent.class);
        var config = new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of("test"), "You are a test agent.",
            List.of(), List.of(), List.of(), List.of(), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of()
        );
        service = new AgentRuntimeService(config, agent, List.of(), new LLMLogger());
    }

    private void mockCallReturns(String text) {
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn(text);
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(msg));
    }

    @Test
    void invokeShouldUseProvidedUserId() {
        mockCallReturns("ok");
        var result = service.invoke("hello", "t1", "alice");

        assertEquals("ok", result.get("response"));
        assertEquals("t1", result.get("thread_id"));
        verify(agent).call(anyList(), argThat((RuntimeContext ctx) ->
            ctx.getUserId().equals("alice")));
    }

    @Test
    void invokeShouldFallbackToVendorKeyWhenUserIdNull() {
        mockCallReturns("ok");
        service.invoke("hello", "t1", null);

        verify(agent).call(anyList(), argThat((RuntimeContext ctx) ->
            ctx.getUserId().equals("acme")));
    }

    @Test
    void invokeShouldFallbackToVendorKeyWhenUserIdBlank() {
        mockCallReturns("ok");
        service.invoke("hello", "t1", "  ");

        verify(agent).call(anyList(), argThat((RuntimeContext ctx) ->
            ctx.getUserId().equals("acme")));
    }

    @Test
    void invokeShouldGenerateThreadIdWhenNull() {
        mockCallReturns("ok");
        var result = service.invoke("hello", null, "alice");

        assertNotNull(result.get("thread_id"));
        assertFalse(((String) result.get("thread_id")).isEmpty());
    }

    @Test
    void invokeShouldSanitizeSlashInThreadId() {
        mockCallReturns("ok");
        service.invoke("hello", "t1", "alice");

        // sessionId = "acme-test-agent:t1" (slug 中的 / 替换为 -)
        verify(agent).call(anyList(), argThat((RuntimeContext ctx) ->
            ctx.getSessionId().equals("acme-test-agent:t1")));
    }

    @Test
    void invokeShouldReturnErrorResponseOnException() {
        when(agent.call(anyList(), any(RuntimeContext.class)))
            .thenThrow(new RuntimeException("LLM down"));
        var result = service.invoke("hello", "t1", "alice");

        assertTrue(((String) result.get("response")).contains("Error: LLM down"));
        assertEquals("t1", result.get("thread_id"));
    }

    @Test
    void invokeStreamShouldEmitWorkingFirst() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.empty());

        var events = service.invokeStream("hello", "t1", "alice")
            .collectList().block();

        assertNotNull(events);
        assertEquals("working", events.get(0).get("state"));
    }

    @Test
    void invokeStreamShouldEmitDoneOnAgentEnd() {
        var endEvent = new io.agentscope.core.event.AgentEndEvent("reply-1");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(endEvent));

        var events = service.invokeStream("hello", "t1", "alice")
            .collectList().block();

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e ->
            "done".equals(e.get("type"))));
        assertTrue(events.stream().anyMatch(e ->
            "completed".equals(e.get("state"))));
    }

    @Test
    void invokeStreamShouldPropagateUserId() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.empty());

        service.invokeStream("hello", "t1", "bob").collectList().block();

        verify(agent).streamEvents(anyList(), argThat((RuntimeContext ctx) ->
            ctx.getUserId().equals("bob")));
    }

    @Test
    void twoArgInvokeShouldDelegateWithVendorKey() {
        mockCallReturns("ok");
        service.invoke("hello", "t1");

        verify(agent).call(anyList(), argThat((RuntimeContext ctx) ->
            ctx.getUserId().equals("acme")));
    }

    @Test
    void tenantPrefixShouldBeSlug() {
        assertEquals("acme/test-agent", service.tenantPrefix());
    }
}
