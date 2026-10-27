package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link TurnStopService} 的目标轮次解析与 fail-closed 语义。
 *
 * <p>覆盖：终态/无 turn → 404；意图行（排队）→ 用意图 replyId；最新事件（执行中）→
 * 用事件 replyId；租约在但无事件 → 409 retryable；HITL 挂起 → 409 走 confirm；
 * 存储读失败/写入失败 → UNAVAILABLE（绝不谎称已停止）。
 */
@ExtendWith(MockitoExtension.class)
class TurnStopServiceTest {

    @Mock private TurnStopStore stopStore;
    @Mock private TurnIntentStore intentStore;
    @Mock private TurnLeaseStore leaseStore;
    @Mock private SessionEventStore eventStore;
    @Mock private AgentRuntimeService runtimeService;

    private TurnStopService service;

    @BeforeEach
    void setUp() {
        service = new TurnStopService(stopStore, intentStore, leaseStore, eventStore, runtimeService);
    }

    @Test
    void pendingConfirmShouldRejectWithWaitingConfirm() {
        when(runtimeService.findPendingConfirm("s1")).thenReturn(java.util.Map.of("tools", "[]"));

        var result = service.request("s1", "u1");

        assertEquals(TurnStopService.Outcome.WAITING_CONFIRM, result.outcome());
        verify(stopStore, never()).request(anyString(), anyString(), anyString());
    }

    @Test
    void queuedTurnShouldUseIntentReplyId() {
        when(runtimeService.findPendingConfirm("s1")).thenReturn(null);
        when(intentStore.find("s1")).thenReturn(new TurnIntentStore.Intent("rid-queued", false, null));
        when(stopStore.request("s1", "rid-queued", "u1"))
            .thenReturn(TurnStopStore.RequestOutcome.REQUESTED);

        var result = service.request("s1", "u1");

        assertEquals(TurnStopService.Outcome.REQUESTED, result.outcome());
        assertEquals("rid-queued", result.replyId());
        verify(stopStore).request("s1", "rid-queued", "u1");
    }

    @Test
    void runningTurnShouldUseLatestNonTerminalEventReplyId() {
        when(runtimeService.findPendingConfirm("s1")).thenReturn(null);
        when(leaseStore.isHeldStrict("s1")).thenReturn(true);
        when(eventStore.findLatestStrict("s1"))
            .thenReturn(new SessionEventStore.EnvelopedEvent(7, "TEXT_BLOCK_DELTA", "{}", "rid-live", null));
        when(stopStore.request("s1", "rid-live", "u1"))
            .thenReturn(TurnStopStore.RequestOutcome.REQUESTED);

        var result = service.request("s1", "u1");

        assertEquals(TurnStopService.Outcome.REQUESTED, result.outcome());
        assertEquals("rid-live", result.replyId());
    }

    @Test
    void terminalTurnShouldNotBeStopped() {
        when(runtimeService.findPendingConfirm("s1")).thenReturn(null);
        when(intentStore.find("s1")).thenReturn(null);
        when(leaseStore.isHeldStrict("s1")).thenReturn(false);

        var result = service.request("s1", "u1");

        assertEquals(TurnStopService.Outcome.NOTHING_TO_STOP, result.outcome());
        assertNull(result.replyId());
        verify(stopStore, never()).request(anyString(), anyString(), anyString());
    }

    @Test
    void leaseHeldWithoutEventsShouldBeRetryableNotReady() {
        when(runtimeService.findPendingConfirm("s1")).thenReturn(null);
        when(eventStore.findLatestStrict("s1")).thenReturn(null);
        when(leaseStore.isHeldStrict("s1")).thenReturn(true);

        var result = service.request("s1", "u1");

        assertEquals(TurnStopService.Outcome.TURN_NOT_READY, result.outcome());
        verify(stopStore, never()).request(anyString(), anyString(), anyString());
    }

    @Test
    void eventStoreFailureShouldBeUnavailable() {
        when(runtimeService.findPendingConfirm("s1")).thenReturn(null);
        when(leaseStore.isHeldStrict("s1")).thenReturn(true);
        when(eventStore.findLatestStrict("s1")).thenThrow(new RuntimeException("redis down"));

        var result = service.request("s1", "u1");

        assertEquals(TurnStopService.Outcome.UNAVAILABLE, result.outcome());
    }

    @Test
    void storeWriteFailureShouldBeUnavailable() {
        when(runtimeService.findPendingConfirm("s1")).thenReturn(null);
        when(leaseStore.isHeldStrict("s1")).thenReturn(false);
        when(intentStore.find("s1")).thenReturn(new TurnIntentStore.Intent("rid-1", false, null));
        when(stopStore.request("s1", "rid-1", "u1"))
            .thenReturn(TurnStopStore.RequestOutcome.UNAVAILABLE);

        var result = service.request("s1", "u1");

        assertEquals(TurnStopService.Outcome.UNAVAILABLE, result.outcome());
        assertEquals("rid-1", result.replyId());
    }

    @Test
    void matchesShouldRequireSameReplyIdFencing() {
        when(stopStore.find("s1")).thenReturn(new TurnStopStore.StopRequest("s1", "rid-2"));

        assertTrue(service.matches("s1", "rid-2"));
        assertTrue(!service.matches("s1", "rid-1"), "replyId 栅栏：不得命中下一轮");
        assertTrue(!service.matches("s2", "rid-2"));
    }

    @Test
    void leaseProbeFailureShouldBeUnavailableInsteadOf404() {
        when(runtimeService.findPendingConfirm("s1")).thenReturn(null);
        when(leaseStore.isHeldStrict("s1"))
            .thenThrow(new IllegalStateException("db down"));

        var result = service.request("s1", "u1");

        assertEquals(TurnStopService.Outcome.UNAVAILABLE, result.outcome());
        verify(stopStore, never()).request(anyString(), anyString(), anyString());
    }
}
