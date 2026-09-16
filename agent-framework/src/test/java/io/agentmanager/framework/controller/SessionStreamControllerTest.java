package io.agentmanager.framework.controller;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionEventTailer;
import io.agentmanager.framework.service.TurnLeaseStore;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话订阅与状态端点测试（{@code GET /threads/{sid}/subscribe}、{@code GET /threads/{sid}/status}）。
 *
 * <p>对话入口的测试见 {@link ChatStreamControllerTest}。
 *
 * <p>注意：sessionId 不能包含 Windows 路径非法字符（如 :），
 * 因为 Controller 内部会调用 PathSafe.sanitize 把 : 替换为 _，
 * 导致 Mockito 精确匹配参数失败。这里统一用 - 代替 :。
 */
class SessionStreamControllerTest {

    private SessionStreamController controller;
    private AgentRuntimeService runtimeService;
    private TurnLeaseStore turnLeaseStore;
    private SessionEventStore eventStore;

    @BeforeEach
    void setUp() {
        runtimeService = mock(AgentRuntimeService.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        eventStore = mock(SessionEventStore.class);

        when(runtimeService.findPendingConfirm(anyString())).thenReturn(null);
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(eventStore.findMaxSeq(anyString())).thenReturn(0);
        when(eventStore.findLatestStrict(anyString())).thenReturn(null);
        when(eventStore.queryAfter(anyString(), anyString(), anyInt())).thenReturn(Flux.empty());
        when(turnLeaseStore.isHeld(anyString())).thenReturn(false);

        var tailer = new SessionEventTailer(eventStore, turnLeaseStore, runtimeService,
            Duration.ofMillis(300));
        controller = new SessionStreamController(runtimeService, turnLeaseStore, eventStore, tailer);
    }

    // ===== GET /subscribe 测试 =====

    @Test
    void subscribeShouldReplayHistoryAndCloseForCompletedSession() {
        var sessionId = "test-user-sub1";
        // 模拟一个已完成的 session：有 AGENT_END 事件
        when(eventStore.findLatestStrict(sessionId)).thenReturn(
            new SessionEventStore.EnvelopedEvent(5, "AGENT_END", "{}", "rid-sub"));
        when(eventStore.queryAfter(eq(sessionId), anyString(), anyInt()))
            .thenReturn(Flux.just(
                new SessionEventStore.EnvelopedEvent(5, "AGENT_END", "{\"type\":\"AGENT_END\"}", "rid-sub")));

        when(eventStore.findMaxSeq(sessionId)).thenReturn(5);

        var frames = controller.subscribe(sessionId, 0, "rid-sub")
            .collectList().block(Duration.ofSeconds(5));

        assertNotNull(frames);
        var data = frames.stream().map(f -> f.data()).toList();
        assertTrue(data.stream().anyMatch(d -> d != null && d.contains("AGENT_END")),
            "应回放历史: " + data);
        assertTrue(data.stream().anyMatch(d -> d != null && d.contains("done")),
            "已完成 session 应追加 done 帧: " + data);
    }

    // ===== GET /status 测试 =====

    @Test
    void statusShouldReturnWorkingWhenLeaseHeld() {
        var sessionId = "test-user-st1";
        when(turnLeaseStore.isHeld(sessionId)).thenReturn(true);
        when(eventStore.findMaxSeq(sessionId)).thenReturn(10);
        when(eventStore.findLatestStrict(sessionId)).thenReturn(
            new SessionEventStore.EnvelopedEvent(10, "TEXT_BLOCK_DELTA", "{}", "rid-st1"));

        var result = controller.status(sessionId).getBody();
        assertEquals("working", result.get("state"));
        assertEquals(10, result.get("latest_event_seq"));
    }

    @Test
    void statusShouldReturnCompletedWhenAgentEnded() {
        var sessionId = "test-user-st2";
        when(turnLeaseStore.isHeld(sessionId)).thenReturn(false);
        when(eventStore.findMaxSeq(sessionId)).thenReturn(20);
        when(eventStore.findLatestStrict(sessionId)).thenReturn(
            new SessionEventStore.EnvelopedEvent(20, "AGENT_END", "{}", "rid-st2"));
        when(runtimeService.findPendingConfirm(sessionId)).thenReturn(null);

        var result = controller.status(sessionId).getBody();
        assertEquals("completed", result.get("state"));
    }

    @Test
    void statusShouldReturnIdleWhenNoEvents() {
        var sessionId = "test-user-st3";
        when(turnLeaseStore.isHeld(sessionId)).thenReturn(false);
        when(eventStore.findMaxSeq(sessionId)).thenReturn(0);
        when(runtimeService.findPendingConfirm(sessionId)).thenReturn(null);

        var result = controller.status(sessionId).getBody();
        assertEquals("idle", result.get("state"));
    }

    @Test
    void statusShouldReturnWaitingConfirmWhenPending() {
        var sessionId = "test-user-st4";
        when(turnLeaseStore.isHeld(sessionId)).thenReturn(false);
        when(eventStore.findMaxSeq(sessionId)).thenReturn(15);
        when(eventStore.findLatestStrict(sessionId)).thenReturn(
            new SessionEventStore.EnvelopedEvent(15, "permission_ask", "{}", "rid-st4"));
        when(runtimeService.findPendingConfirm(sessionId)).thenReturn(
            java.util.Map.of("reply_id", "rid-st4", "tools", "[]"));

        var result = controller.status(sessionId).getBody();
        assertEquals("waiting_confirm", result.get("state"));
    }

    // ===== SSE 序列化器测试（MCP Apps ui 元数据） =====

    @Test
    void serializerShouldIncludeUiMetadataOnToolCallStart() {
        var tc = new io.agentscope.core.event.ToolCallStartEvent("reply-u", "call-u", "get_weather");
        String json = AgentEventSseSerializer.payload(tc, "ui://weather/mcp-app.html", "weather");
        assertTrue(json.contains("\"ui\""), "TOOL_CALL_START 应携带 ui 字段: " + json);
        assertTrue(json.contains("\"resourceUri\":\"ui://weather/mcp-app.html\""), "应携带 resourceUri: " + json);
        assertTrue(json.contains("\"server\":\"weather\""), "应携带 server: " + json);
    }

    @Test
    void serializerShouldNotIncludeUiWhenNull() {
        var tc = new io.agentscope.core.event.ToolCallStartEvent("reply-u", "call-u", "echo");
        String json = AgentEventSseSerializer.payload(tc, null, null);
        assertTrue(!json.contains("\"ui\""), "无 UI 工具不应携带 ui 字段: " + json);
        assertTrue(json.contains("\"toolName\":\"echo\""), "原词表字段保持: " + json);
    }
}
