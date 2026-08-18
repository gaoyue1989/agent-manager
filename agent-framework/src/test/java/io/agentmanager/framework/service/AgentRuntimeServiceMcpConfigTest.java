package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * AgentRuntimeService 补充测试：MCP system prompt 拼接 + invokeStream 各事件分支 + 异常分支。
 */
class AgentRuntimeServiceMcpConfigTest {

    private HarnessAgent agent;

    private HarnessAgent newAgent() {
        agent = mock(HarnessAgent.class);
        return agent;
    }

    private AgentRuntimeService build(List<Map<String, Object>> mcpConfigs) {
        var config = new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of("test"), "you are a helper.",
            List.of(), List.of(), List.of(), List.of("Read", "Bash"), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of()
        );
        return new AgentRuntimeService(config, newAgent(), mcpConfigs, new LLMLogger(), new SessionEventBus());
    }

    private AgentRuntimeService newService(List<Map<String, Object>> mcpConfigs) {
        return build(mcpConfigs);
    }

    // ---------- buildSystemPrompt (MCP 注入) ----------

    @Test
    void buildSystemPromptShouldAppendMcpWhenConfigsPresent() {
        var mcp = List.of(Map.<String, Object>of(
            "server", "weather-service",
            "tools", Map.of("selectedTools", List.of(
                Map.of("name", "get_temp", "enabled", true),
                Map.of("name", "get_humid", "enabled", false),
                Map.of("name", "get_wind", "enabled", true)
            ))
        ));
        var service = newService(mcp);

        var prompt = service.buildSystemPrompt();

        assertTrue(prompt.contains("weather-service"));
        assertTrue(prompt.contains("2 tools"));
        assertTrue(prompt.contains("get_temp"));
        assertTrue(prompt.contains("get_wind"));
        assertFalse(prompt.contains("get_humid"));
    }

    @Test
    void buildSystemPromptTruncatesToolNamesAtTen() {
        var tools = new java.util.ArrayList<Map<String, Object>>();
        for (var i = 0; i < 15; i++) {
            tools.add(Map.of("name", "tool" + i, "enabled", true));
        }
        var mcpConfigs = List.of(Map.<String, Object>of(
            "server", "big-server",
            "tools", Map.of("selectedTools", tools)
        ));
        var service = newService(mcpConfigs);

        var prompt = service.buildSystemPrompt();

        assertTrue(prompt.contains("tool0"));
        assertTrue(prompt.contains("tool9"));
        assertFalse(prompt.contains("tool10"));
    }

    @Test
    void buildSystemPromptWithoutMcpReturnsBaseOnly() {
        var service = newService(List.of());
        assertEquals("you are a helper.", service.buildSystemPrompt());
    }

    @Test
    void accessorsExposeOafConfigFields() {
        var service = newService(List.of());
        assertEquals("Test agent", service.description());
        assertEquals("acme/test-agent", service.tenantPrefix());
        assertEquals(List.of("Read", "Bash"), service.toolsList());
        assertNotNull(service.oafConfig());
    }

    // ---------- invokeStream 事件分支 ----------

    @Test
    void invokeStreamShouldEmitTokenOnTextDelta() {
        var service = newService(List.of());
        var delta = new TextBlockDeltaEvent("reply-1", "block-1", "hello");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(delta));

        var events = service.invokeStream("hi", "t1", "alice").collectList().block();

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e ->
            "token".equals(e.get("type")) && "hello".equals(e.get("token"))));
    }

    @Test
    void invokeStreamShouldEmitToolCallStart() {
        var service = newService(List.of());
        var tc = new ToolCallStartEvent("reply-1", "call-1", "get_weather");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(tc));

        var events = service.invokeStream("hi", "t1", "alice").collectList().block();

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e ->
            "tool_call".equals(e.get("type")) && "get_weather".equals(e.get("name"))));
    }

    @Test
    void invokeStreamShouldEmitToolResultEnd() {
        var service = newService(List.of());
        var tr = new ToolResultEndEvent("call-1", "task-1", "reply-1", ToolResultState.SUCCESS);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(tr));

        var events = service.invokeStream("hi", "t1", "alice").collectList().block();

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e ->
            "tool_result".equals(e.get("type")) && "SUCCESS".equals(e.get("state"))));
    }

    @Test
    void invokeStreamShouldEmitWorkingThenDoneOnEnd() {
        var service = newService(List.of());
        var end = new AgentEndEvent("reply-2");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(end));

        var events = service.invokeStream("hi", "t1", "alice").collectList().block();

        assertNotNull(events);
        assertEquals("working", events.get(0).get("state"));
        assertTrue(events.stream().anyMatch(e -> "done".equals(e.get("type")) && e.size() == 1));
        assertTrue(events.stream().anyMatch(e -> "completed".equals(e.get("state"))));
    }

    @Test
    void invokeStreamShouldEmitErrorAndDoneOnFailure() {
        var service = newService(List.of());
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.error(new RuntimeException("stream broke")));

        var events = service.invokeStream("hi", "t1", "alice").collectList().block();

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> "error".equals(e.get("type"))));
        assertTrue(events.stream().anyMatch(e -> "done".equals(e.get("type"))));
    }

    @Test
    void invokeStreamShouldGenerateThreadIdWhenNull() {
        var service = newService(List.of());
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        var events = service.invokeStream("hi", null, "alice").collectList().block();

        assertNotNull(events);
        assertFalse(((String) events.get(0).get("id")).isEmpty());
    }

    @Test
    void invokeStreamShouldFallbackUserIdToVendorKey() {
        var service = newService(List.of());
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        service.invokeStream("hi", "t1", null).collectList().block();

        verify(agent).streamEvents(anyList(), argThat((RuntimeContext ctx) ->
            ctx.getUserId().equals("acme")));
    }

    // ---------- setAgent ----------

    @Test
    void setAgentShouldReplaceUnderlyingAgent() {
        var service = newService(List.of());
        var newAgent = harnessAgent();
        service.setAgent(newAgent);

        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("from-new");
        when(newAgent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(msg));

        var result = service.invoke("hi", "t1", "u");
        assertEquals("from-new", result.get("response"));
    }

    private HarnessAgent harnessAgent() {
        return mock(HarnessAgent.class);
    }
}