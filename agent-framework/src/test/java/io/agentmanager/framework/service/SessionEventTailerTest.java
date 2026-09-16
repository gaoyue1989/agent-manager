package io.agentmanager.framework.service;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SessionEventTailer 终止判定单测（durable-sse-multinode-plan §3.4.2）。
 *
 * <p>判定表：
 * <ul>
 *   <li>lease 被持有 → running</li>
 *   <li>lease 释放 + 最新事件终态（AGENT_END / error）→ finished</li>
 *   <li>lease 释放 + 有待确认上下文 → finished（HITL 是 turn 边界）</li>
 *   <li>lease 释放 + 最新事件非终态 + 无待确认 → interrupted</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SessionEventTailerTest {

    @Mock
    private SessionEventStore eventStore;

    @Mock
    private TurnLeaseStore turnLeaseStore;

    @Mock
    private AgentRuntimeService runtimeService;

    private SessionEventTailer tailer;

    @BeforeEach
    void setUp() {
        tailer = new SessionEventTailer(eventStore, turnLeaseStore, runtimeService,
            Duration.ofMillis(50));
    }

    @Test
    void runningWhenLeaseHeld() {
        when(turnLeaseStore.isHeld("sid-1")).thenReturn(true);
        var probe = tailer.probe("sid-1");
        assertTrue(probe.running());
        assertFalse(probe.finished());
        assertFalse(probe.interrupted());
        // lease 被持有时不应产生额外查询
        verifyNoInteractions(eventStore);
    }

    @Test
    void finishedWhenLatestIsAgentEnd() {
        when(turnLeaseStore.isHeld("sid-2")).thenReturn(false);
        when(eventStore.findLatest("sid-2")).thenReturn(
            new SessionEventStore.EnvelopedEvent(10, "AGENT_END", "{}", "rid"));
        var probe = tailer.probe("sid-2");
        assertTrue(probe.finished());
        assertFalse(probe.interrupted());
    }

    @Test
    void finishedWhenHitlPendingConfirm() {
        when(turnLeaseStore.isHeld("sid-3")).thenReturn(false);
        when(eventStore.findLatest("sid-3")).thenReturn(
            new SessionEventStore.EnvelopedEvent(7, "permission_ask", "{}", "rid"));
        when(runtimeService.findPendingConfirm("sid-3")).thenReturn(java.util.Map.of("tools", "[]"));
        var probe = tailer.probe("sid-3");
        assertTrue(probe.finished());
        assertFalse(probe.interrupted());
    }

    @Test
    void interruptedWhenNoLeaseNoPendingNoTerminal() {
        when(turnLeaseStore.isHeld("sid-4")).thenReturn(false);
        when(eventStore.findLatest("sid-4")).thenReturn(
            new SessionEventStore.EnvelopedEvent(7, "TEXT_BLOCK_DELTA", "{}", "rid"));
        when(runtimeService.findPendingConfirm("sid-4")).thenReturn(null);
        var probe = tailer.probe("sid-4");
        assertTrue(probe.interrupted());
        assertFalse(probe.finished());
    }

    @Test
    void finishedWhenNoEventsAtAll() {
        when(turnLeaseStore.isHeld("sid-5")).thenReturn(false);
        when(eventStore.findLatest("sid-5")).thenReturn(null);
        var probe = tailer.probe("sid-5");
        assertTrue(probe.finished());
        assertFalse(probe.interrupted());
    }

    @Test
    void probeWithPreFetchedDataUsesThem() {
        when(turnLeaseStore.isHeld("sid-6")).thenReturn(false);
        var latest = new SessionEventStore.EnvelopedEvent(3, "AGENT_END", "{}", "rid");
        var probe = tailer.probe("sid-6", latest, false);
        assertTrue(probe.finished());
        // 不应再自行查询
        verifyNoInteractions(eventStore, runtimeService);
    }

    @Test
    void terminalErrorTypeCountsAsFinished() {
        when(turnLeaseStore.isHeld("sid-7")).thenReturn(false);
        when(eventStore.findLatest("sid-7")).thenReturn(
            new SessionEventStore.EnvelopedEvent(4, "error", "{}", "rid"));
        assertTrue(tailer.probe("sid-7").finished());
    }
}
