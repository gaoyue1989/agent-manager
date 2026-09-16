package io.agentmanager.framework.service;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

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

    // ===== tail：回放 + 游标追赶（观察者路径，只读 DB） =====

    @Test
    void tailReplaysThenEmitsDoneWhenTurnFinished() {
        // 回放取 seq>0 拿到 delta；追赶以游标 1 继续，拿到 AGENT_END → 补 done 帧
        when(eventStore.queryAfter("sid-t1", "rid", 0)).thenReturn(Flux.just(
            new SessionEventStore.EnvelopedEvent(1, "TEXT_BLOCK_DELTA",
                "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"hi\"}", "rid")));
        when(eventStore.queryAfter("sid-t1", "rid", 1)).thenReturn(Flux.just(
            new SessionEventStore.EnvelopedEvent(2, "AGENT_END", "{}", "rid")));

        // 帧序：回放的 delta(seq=1) → 追赶到的终态事件(seq=2，证明游标从 1 续起) → 补的 done 帧
        StepVerifier.create(tailer.tail("sid-t1", "rid", 0))
            .expectNextMatches(sse -> sse.data() != null && sse.data().contains("hi"))
            .expectNextMatches(sse -> "2".equals(sse.id()) && sse.data() != null
                && sse.data().contains("\"replyId\":\"rid\""))
            .expectNextMatches(sse -> sse.data() != null && sse.data().contains("done"))
            .verifyComplete();
    }

    @Test
    void tailEmitsInterruptedWhenExecutorCrashed() {
        // 回放拿到 seq=4 的 delta；追赶以游标 4 继续，无新事件
        // → 首轮立即探测：lease 已释放、最新非终态、无待确认 → interrupted
        when(eventStore.queryAfter("sid-t2", "rid", 0)).thenReturn(Flux.just(
            new SessionEventStore.EnvelopedEvent(4, "TEXT_BLOCK_DELTA",
                "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"partial\"}", "rid")));
        when(eventStore.queryAfter("sid-t2", "rid", 4)).thenReturn(Flux.empty());
        when(turnLeaseStore.isHeld("sid-t2")).thenReturn(false);
        when(eventStore.findLatest("sid-t2")).thenReturn(
            new SessionEventStore.EnvelopedEvent(4, "TEXT_BLOCK_DELTA", "{}", "rid"));
        when(runtimeService.findPendingConfirm("sid-t2")).thenReturn(null);

        StepVerifier.create(tailer.tail("sid-t2", "rid", 0))
            .expectNextMatches(sse -> sse.data().contains("partial"))
            .expectNextMatches(sse -> sse.data().contains("interrupted"))
            .verifyComplete();
    }

    @Test
    void tailNeverTouchesLocalSink() {
        // 终止路径不应依赖任何进程内状态：本用例不含 SessionEventBus，
        // 若 tail 实现引用了本地 sink，构造期即失败。
        when(turnLeaseStore.isHeld("sid-t3")).thenReturn(false);
        when(eventStore.queryAfter("sid-t3", null, 0)).thenReturn(Flux.empty());
        when(eventStore.findLatest("sid-t3")).thenReturn(null);

        StepVerifier.create(tailer.tail("sid-t3", null, 0))
            .expectNextMatches(sse -> sse.data().contains("done"))
            .verifyComplete();
    }

    @Test
    void observePathPayloadMatchesBusPayload() {
        // 同一事件：执行副本走本地 sink、观察者走 DB 追赶，前端必须拿到同样的 payload。
        // 两条路径各自独立实现 toSSE（观察者路径刻意不依赖 SessionEventBus），
        // 因此这里逐字节比对，用来捕捉两者漂移。
        var event = new SessionEventStore.EnvelopedEvent(
            3, "AGENT_END", "{\"type\":\"AGENT_END\"}", "rid-x");
        when(eventStore.queryAfter("sid-x", "rid-x", 0)).thenReturn(Flux.just(event));

        var bus = new SessionEventBus(eventStore,
            Duration.ofMillis(100), Duration.ofMinutes(5), 64);
        var viaSink = bus.subscribe("sid-x", 0, "rid-x").next().block();
        var observed = tailer.tail("sid-x", "rid-x", 0).next().block();

        assertNotNull(viaSink);
        assertNotNull(observed);
        assertEquals(viaSink.data(), observed.data(),
            "两条路径的 payload 必须一致（执行副本 vs 观察者）");
        assertEquals(viaSink.id(), observed.id());
        assertTrue(observed.data().contains("\"replyId\":\"rid-x\""),
            "观察者路径的 payload 未注入 replyId: " + observed.data());
        assertEquals("3", observed.id());
    }
}
