package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionEventTailer;
import io.agentmanager.framework.service.TurnLeaseStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionStreamControllerStatusTest {

    @Mock private TurnLeaseStore turnLeaseStore;
    @Mock private AgentRuntimeService runtimeService;
    @Mock private SessionEventStore eventStore;

    private SessionEventTailer tailer;
    private SessionStreamController controller;

    @BeforeEach
    void setUp() {
        tailer = new SessionEventTailer(eventStore, turnLeaseStore, runtimeService,
            Duration.ofMillis(300));
        controller = new SessionStreamController(runtimeService, turnLeaseStore, eventStore, tailer);
    }

    @Test
    void interruptedWhenLeaseGoneAndNoTerminalEvent() {
        when(turnLeaseStore.isHeld("sid-1")).thenReturn(false);
        when(eventStore.findLatestStrict("sid-1")).thenReturn(
            new SessionEventStore.EnvelopedEvent(9, "TEXT_BLOCK_DELTA", "{}", "rid-1"));
        when(runtimeService.findPendingConfirm("sid-1")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-1").getBody();
        assertEquals("interrupted", body.get("state"));
        assertEquals(9, body.get("latest_event_seq"));
    }

    @Test
    void workingWhenLeaseHeldEvenWithoutLocalSink() {
        when(turnLeaseStore.isHeld("sid-2")).thenReturn(true);
        when(eventStore.findLatestStrict("sid-2")).thenReturn(
            new SessionEventStore.EnvelopedEvent(3, "TEXT_BLOCK_DELTA", "{}", "rid-2"));
        when(runtimeService.findPendingConfirm("sid-2")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-2").getBody();
        // 跨副本关键点：本 Pod 没有 sink，也必须报 working
        assertEquals("working", body.get("state"));
    }

    @Test
    void completedWhenLatestIsAgentEnd() {
        when(turnLeaseStore.isHeld("sid-3")).thenReturn(false);
        when(eventStore.findLatestStrict("sid-3")).thenReturn(
            new SessionEventStore.EnvelopedEvent(12, "AGENT_END", "{}", "rid-3"));
        when(runtimeService.findPendingConfirm("sid-3")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-3").getBody();
        assertEquals("completed", body.get("state"));
        assertEquals(12, body.get("latest_event_seq"));
    }

    @Test
    void waitingConfirmTakesPrecedence() {
        when(eventStore.findLatestStrict("sid-4")).thenReturn(
            new SessionEventStore.EnvelopedEvent(5, "permission_ask", "{}", "rid-4"));
        when(runtimeService.findPendingConfirm("sid-4")).thenReturn(Map.of("tools", "[]"));

        Map<String, Object> body = controller.status("sid-4").getBody();
        assertEquals("waiting_confirm", body.get("state"));
    }

    @Test
    void idleWhenNoEvents() {
        when(turnLeaseStore.isHeld("sid-5")).thenReturn(false);
        when(eventStore.findLatestStrict("sid-5")).thenReturn(null);
        when(runtimeService.findPendingConfirm("sid-5")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-5").getBody();
        assertEquals("idle", body.get("state"));
        assertEquals(0, body.get("latest_event_seq"));
    }

    /**
     * 存储不可用时必须 503，而不是一个 {@code latest_event_seq: 0} 的**成功**响应。
     *
     * <p>这是本端点最重要的失败语义：前端 {@code chat.js:463} 会无条件
     * {@code lastEventId = status.latest_event_seq || 0}，所以一个发出去的 0 会把健康客户端的
     * 游标重置到流首，Redis 一恢复就是整场会话重放。503 让 {@code api.js} 的 {@code get()}
     * 抛错，落到 {@code tryResumeSSE} 的 catch（{@code chat.js:514}）保留游标、跳过恢复。
     */
    @Test
    void serviceUnavailableWhenStoreCannotBeRead() {
        when(runtimeService.findPendingConfirm("sid-6")).thenReturn(null);
        when(eventStore.findLatestStrict("sid-6"))
            .thenThrow(new RuntimeException("redis connection refused"));

        var resp = controller.status("sid-6");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals("event_store_unavailable", resp.getBody().get("error"));
        // 关键：不能带上任何会被前端当成游标的字段
        assertFalse(resp.getBody().containsKey("latest_event_seq"),
            "503 响应不得携带 latest_event_seq，否则前端仍可能把游标重置为 0");
    }
}
