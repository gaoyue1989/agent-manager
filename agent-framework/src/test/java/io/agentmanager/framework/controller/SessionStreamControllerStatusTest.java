package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionEventTailer;
import io.agentmanager.framework.service.TurnLeaseStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionStreamControllerStatusTest {

    @Mock private TurnLeaseStore turnLeaseStore;
    @Mock private AgentRuntimeService runtimeService;
    @Mock private SessionEventBus eventBus;
    @Mock private SessionEventStore eventStore;

    private SessionEventTailer tailer;
    private SessionStreamController controller;

    @BeforeEach
    void setUp() {
        tailer = new SessionEventTailer(eventStore, turnLeaseStore, runtimeService,
            Duration.ofMillis(300));
        controller = new SessionStreamController(runtimeService, turnLeaseStore, eventBus, eventStore, tailer);
    }

    @Test
    void interruptedWhenLeaseGoneAndNoTerminalEvent() {
        when(turnLeaseStore.isHeld("sid-1")).thenReturn(false);
        when(eventStore.findLatest("sid-1")).thenReturn(
            new SessionEventStore.EnvelopedEvent(9, "TEXT_BLOCK_DELTA", "{}", "rid-1"));
        when(runtimeService.findPendingConfirm("sid-1")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-1");
        assertEquals("interrupted", body.get("state"));
        assertEquals(9, body.get("latest_event_seq"));
    }

    @Test
    void workingWhenLeaseHeldEvenWithoutLocalSink() {
        when(turnLeaseStore.isHeld("sid-2")).thenReturn(true);
        when(eventStore.findLatest("sid-2")).thenReturn(
            new SessionEventStore.EnvelopedEvent(3, "TEXT_BLOCK_DELTA", "{}", "rid-2"));
        when(runtimeService.findPendingConfirm("sid-2")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-2");
        assertEquals("working", body.get("state"));
        // 跨副本关键点：本 Pod 没有 sink，也必须报 working
        verify(eventBus, never()).turnStatus(anyString());
    }

    @Test
    void completedWhenLatestIsAgentEnd() {
        when(turnLeaseStore.isHeld("sid-3")).thenReturn(false);
        when(eventStore.findLatest("sid-3")).thenReturn(
            new SessionEventStore.EnvelopedEvent(12, "AGENT_END", "{}", "rid-3"));
        when(runtimeService.findPendingConfirm("sid-3")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-3");
        assertEquals("completed", body.get("state"));
        assertEquals(12, body.get("latest_event_seq"));
    }

    @Test
    void waitingConfirmTakesPrecedence() {
        when(eventStore.findLatest("sid-4")).thenReturn(
            new SessionEventStore.EnvelopedEvent(5, "permission_ask", "{}", "rid-4"));
        when(runtimeService.findPendingConfirm("sid-4")).thenReturn(Map.of("tools", "[]"));

        Map<String, Object> body = controller.status("sid-4");
        assertEquals("waiting_confirm", body.get("state"));
    }

    @Test
    void idleWhenNoEvents() {
        when(turnLeaseStore.isHeld("sid-5")).thenReturn(false);
        when(eventStore.findLatest("sid-5")).thenReturn(null);
        when(runtimeService.findPendingConfirm("sid-5")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-5");
        assertEquals("idle", body.get("state"));
        assertEquals(0, body.get("latest_event_seq"));
    }
}
