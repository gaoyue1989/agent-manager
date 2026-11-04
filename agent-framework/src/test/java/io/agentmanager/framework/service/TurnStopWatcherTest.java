package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.agentmanager.framework.config.TurnStopSettings;

/**
 * {@link TurnStopWatcher}：全局轮询按 replyId 匹配本 Pod 活跃 turn 并重复投递 interrupt。
 */
@ExtendWith(MockitoExtension.class)
class TurnStopWatcherTest {

    @Mock private TurnStopStore stopStore;
    @Mock private TurnControlRegistry registry;

    private TurnStopWatcher watcher;

    @BeforeEach
    void setUp() {
        watcher = new TurnStopWatcher(stopStore, registry,
            new TurnStopSettings(true, 1000, 15000));
    }

    @Test
    void pollShouldInterruptMatchingLocalTurnAndKeepRowForRepeatDelivery() {
        when(stopStore.findAll()).thenReturn(List.of(
            new TurnStopStore.StopRequest("s1", "rid-1"),
            new TurnStopStore.StopRequest("s2", "rid-other")));
        when(registry.interrupt("s1", "rid-1")).thenReturn(true);

        watcher.register("s1", "rid-1");
        watcher.poll();
        watcher.poll();

        verify(registry, times(2)).interrupt("s1", "rid-1");
        verify(registry, never()).interrupt("s2", "rid-other");
        assertTrue(watcher.isStopRequested("rid-1"));
    }

    @Test
    void nonMatchingRowShouldBeIgnored() {
        when(stopStore.findAll()).thenReturn(List.of(
            new TurnStopStore.StopRequest("s1", "rid-stale")));

        watcher.register("s1", "rid-current");
        watcher.poll();

        verify(registry, never()).interrupt(anyString(), anyString());
        assertFalse(watcher.isStopRequested("rid-current"));
    }

    @Test
    void unregisterShouldRemoveAndConsumeRow() {
        watcher.register("s1", "rid-1");
        assertEquals(1, watcher.activeCount());

        watcher.unregister("s1", "rid-1");

        assertEquals(0, watcher.activeCount());
        verify(stopStore).consume("s1", "rid-1");
    }

    @Test
    void disabledSettingsShouldSkipPolling() {
        var disabled = new TurnStopWatcher(stopStore, registry,
            new TurnStopSettings(false, 1000, 15000));
        disabled.register("s1", "rid-1");

        disabled.poll();

        verify(stopStore, never()).findAll();
        verify(registry, never()).interrupt(anyString(), anyString());
    }

    @Test
    void queuedRegistrationShouldMarkWithoutConsumingRowOrInterrupt() {
        // issue #228：turn_stop 行 = 唯一权威。queued 派发只置内存标记、不消费行——
        // 消费权归租约交接点（unregisterRegistrationOnly 置位 → unregister）与 turn 收尾；
        // 若在此提前消费，「loop 末次检查已过 + 本拍 tryAcquire 得手」的竞态会静默丢请求。
        when(stopStore.findAll()).thenReturn(List.of(
            new TurnStopStore.StopRequest("s1", "rid-q")));

        watcher.registerQueued("s1", "rid-q");
        watcher.poll();
        watcher.poll();

        assertTrue(watcher.isStopRequested("rid-q"));
        verify(registry, never()).interrupt(anyString(), anyString());
        verify(stopStore, never()).consume(anyString(), anyString());
    }

    @Test
    void unregisterRegistrationOnlyShouldReportQueuedStopFlagAtomically() {
        // 派发命中排队登记后，原子取回 → true（调用方须当场取消：消费行 + cancelled 收流）
        when(stopStore.findAll()).thenReturn(List.of(
            new TurnStopStore.StopRequest("s1", "rid-h")));
        watcher.registerQueued("s1", "rid-h");
        watcher.poll();

        assertTrue(watcher.unregisterRegistrationOnly("rid-h"));
        assertEquals(0, watcher.activeCount());

        // 交接后的新登记（执行态）：未命中停止 → false，turn_stop 行留执行态派发
        watcher.register("s1", "rid-h");
        assertFalse(watcher.unregisterRegistrationOnly("rid-h"));
    }
}
