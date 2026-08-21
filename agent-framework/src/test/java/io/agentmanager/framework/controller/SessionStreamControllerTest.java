package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentmanager.framework.service.UiContextStore;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话单次流端点测试（无状态单次流架构：POST /threads/{sessionId}/chat 事件直吐）。
 * 直接消费 controller.chat() 返回的 Flux 断言 SSE 帧（避免 MockMvc 异步收集歧义）。
 */
class SessionStreamControllerTest {

    private SessionStreamController controller;
    private ChatUiChannel chatChannel;
    private AgentRuntimeService runtimeService;
    private McpToolRegistrar mcpToolRegistrar;
    private TurnLeaseStore turnLeaseStore;
    private ToolAuditStore toolAuditStore;

    @BeforeEach
    void setUp() {
        chatChannel = mock(ChatUiChannel.class);
        runtimeService = mock(AgentRuntimeService.class);
        mcpToolRegistrar = mock(McpToolRegistrar.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        toolAuditStore = mock(ToolAuditStore.class);
        when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofSeconds(20));
        controller = new SessionStreamController(chatChannel, runtimeService, mcpToolRegistrar,
            turnLeaseStore, toolAuditStore);
    }

    private List<String> collect(String sid, String message, String userId) {
        var frames = controller.chat(sid, new SessionStreamController.ChatRequest(message, userId))
            .collectList().block();
        return frames == null ? List.of() : frames.stream().map(f -> f.data()).toList();
    }

    @Test
    void chatShouldEmitEventsDirectly() {
        var sessionId = "test-user:s1";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-1");
        var delta = new TextBlockDeltaEvent("reply-1", "block-1", "Hi");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent) delta));

        var frames = collect(sessionId, "hello", "alice");
        assertTrue(frames.stream().anyMatch(f -> f.contains("TEXT_BLOCK_DELTA")), "应直吐: " + frames);

        verify(turnLeaseStore).tryAcquire(sessionId);
        verify(turnLeaseStore).release(sessionId, "tok-1");
    }

    @Test
    void chatShouldReleaseLeaseOnNaturalComplete() {
        var sessionId = "test-user:s2";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-2");
        when(chatChannel.sendStream(any(ChatUiRequest.class))).thenReturn(Flux.empty());

        var frames = collect(sessionId, "hello", null);
        assertNotNull(frames);
        verify(turnLeaseStore).release(sessionId, "tok-2");
    }

    @Test
    void chatShouldQueueWithWaitingFramesWhenLeaseBusy() {
        var sessionId = "test-user:s3";
        // 第一次被占用（返回 null），第二次拿到 → 等待窗口内发出 waiting 帧
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn(null).thenReturn("tok-3");
        when(chatChannel.sendStream(any(ChatUiRequest.class))).thenReturn(Flux.empty());

        var frames = collect(sessionId, "hello", null);
        assertTrue(frames.stream().anyMatch(f -> f.contains("waiting")), "排队期间应发 waiting 帧: " + frames);
    }

    @Test
    void chatShouldRejectBlankMessage() {
        var frames = collect("test-user:s4", "", "alice");
        assertTrue(frames.stream().anyMatch(f -> f.contains("message is required")),
            "空消息应返回 error 帧: " + frames);
    }

    @Test
    void chatShouldAttachSessionKeyToUserMessage() {
        var sessionId = "test-user:s5";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-5");
        when(chatChannel.sendStream(any(ChatUiRequest.class))).thenReturn(Flux.empty());

        var captor = org.mockito.ArgumentCaptor.forClass(ChatUiRequest.class);
        collect(sessionId, "hi", "alice");
        verify(chatChannel).sendStream(captor.capture());
        var messages = captor.getValue().messages();
        assertEquals(1, messages.size(), "只应发送用户消息（ui_context 由 Hook 注入，不进消息列表）");
        assertEquals(MsgRole.USER, messages.get(0).getRole());
        assertEquals("hi", messages.get(0).getTextContent());
        assertEquals(sessionId,
            messages.get(0).getMetadata().get(UiContextStore.METADATA_SESSION_KEY),
            "用户消息 metadata 应携带会话 key，供 UiContextInjectionHook 读取");
    }

    // ===== MCP Apps: TOOL_CALL_START ui 词表扩展 =====

    @Test
    void serializerShouldIncludeUiMetadataOnToolCallStart() {
        var tc = new io.agentscope.core.event.ToolCallStartEvent("reply-u", "call-u", "get_weather");
        String json = AgentEventSseSerializer.payload(tc, "ui://weather/mcp-app.html", "weather");
        assertTrue(json.contains("\"ui\""), "TOOL_CALL_START 应携带 ui 字段: " + json);
        assertTrue(json.contains("\"resourceUri\":\"ui://weather/mcp-app.html\""), "应携带 resourceUri: " + json);
        assertTrue(json.contains("\"server\":\"weather\""), "应携带 server: " + json);
        assertTrue(json.contains("\"toolName\":\"get_weather\""), "原词表字段保持: " + json);
    }

    @Test
    void serializerShouldNotIncludeUiWhenNull() {
        var tc = new io.agentscope.core.event.ToolCallStartEvent("reply-u", "call-u", "echo");
        String json = AgentEventSseSerializer.payload(tc, null, null);
        assertTrue(!json.contains("\"ui\""), "无 UI 工具不应携带 ui 字段: " + json);
        assertTrue(json.contains("\"toolName\":\"echo\""), "原词表字段保持: " + json);
    }

    @Test
    void sseShouldCarryUiForUiToolViaResolver() {
        var ref = new McpToolRegistrar.UiRef("ui://weather/mcp-app.html", "weather");
        when(mcpToolRegistrar.resolveUiRef("get_weather")).thenReturn(ref);
        var sessionId = "test-user:ui1";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-u1");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent)
                new io.agentscope.core.event.ToolCallStartEvent("r", "c", "get_weather")));

        var frames = collect(sessionId, "go", null);
        assertTrue(frames.stream().anyMatch(f -> f.contains("\"resourceUri\":\"ui://weather/mcp-app.html\"")),
            "SSE 事件应携带 ui 元数据: " + frames);
    }

    @Test
    void chatShouldAuditToolStartEvents() {
        var sessionId = "test-user:audit1";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-a1");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent)
                new io.agentscope.core.event.ToolCallStartEvent("r", "c", "get_weather")));

        collect(sessionId, "go", null);
        verify(toolAuditStore).record(eq(sessionId), eq("get_weather"), eq("c"),
            eq("TOOL_CALL_START"), anyString());
    }
}