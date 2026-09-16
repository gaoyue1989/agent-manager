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

    @Test
    void tailFollowsEventsWrittenByAnotherReplica() {
        // 场景：执行发生在 Pod A，观察者在 Pod B。
        // Pod B 没有该 session 的 sink，只能读 DB。
        when(eventStore.queryAfter("sid-x", "rid-x", 0))
            .thenReturn(Flux.empty())                                   // 回放：Pod A 尚未产出
            .thenReturn(Flux.empty())                                   // 追赶第 1 轮：仍为空
            .thenReturn(Flux.just(new SessionEventStore.EnvelopedEvent(1, "TEXT_BLOCK_DELTA",
                "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"from-A\"}", "rid-x")));
        when(eventStore.queryAfter("sid-x", "rid-x", 1))
            .thenReturn(Flux.just(new SessionEventStore.EnvelopedEvent(2, "AGENT_END", "{}", "rid-x")));
        // 追赶第 1 轮为空时会立即探测一次：Pod A 仍在执行 → RUNNING → 继续轮询
        when(turnLeaseStore.isHeld("sid-x")).thenReturn(true);

        // 帧序与 tailReplaysThenEmitsDoneWhenTurnFinished 一致：
        // Pod A 写入的终态事件本身先发，随后才是本地补的 done 帧
        StepVerifier.create(tailer.tail("sid-x", "rid-x", 0))
            .expectNextMatches(sse -> sse.data().contains("from-A"))
            .expectNextMatches(sse -> "2".equals(sse.id()))
            .expectNextMatches(sse -> sse.data().contains("done"))
            .verifyComplete();
    }

    @Test
    void tailEmitsHeartbeatWhileTurnStaysQuiet() {
        // 观察者路径以长静默为常态（执行在另一个副本上跑长工具调用）。没有 comment 帧，
        // 入口代理会在读超时处切断连接——这是被删掉的 SessionEventBus.subscribe 原本提供过的保障。
        when(turnLeaseStore.isHeld("sid-hb")).thenReturn(true);
        when(eventStore.queryAfter(eq("sid-hb"), isNull(), anyInt())).thenReturn(Flux.empty());

        var hbTailer = new SessionEventTailer(eventStore, turnLeaseStore, runtimeService,
            Duration.ofMillis(20), Duration.ofMillis(50));

        var frames = hbTailer.tail("sid-hb", null, -1)
            .take(2)
            .collectList().block(Duration.ofSeconds(5));

        assertNotNull(frames);
        assertEquals(2, frames.size());
        assertTrue(frames.stream().allMatch(f -> "hb".equals(f.comment())),
            "静默期间应补 comment 心跳帧: " + frames);
    }

    @Test
    void tailDeliversTerminalEventThatLandsBetweenQueryAndProbe() {
        // 竞态：终止行恰好落在循环内的 queryAfter 与随后的 probe 之间——page 为空，
        // probe 却已判定 finished。若不补一次追赶查询，终止事件自身（这里是 error）
        // 会被静默丢弃，客户端只收到 done，把一次失败当成正常完成。
        var errRow = new SessionEventStore.EnvelopedEvent(
            5, "error", "{\"type\":\"error\",\"error\":\"boom\"}", "rid-race");
        // 第 1 次查询（循环内）终止行尚未落库；第 2 次（补发）拿到了
        when(eventStore.queryAfter("sid-race", null, -1))
            .thenReturn(Flux.empty(), Flux.just(errRow));
        when(turnLeaseStore.isHeld("sid-race")).thenReturn(false);
        // 最新事件已是终态 → probe 短路为 finished，不会再查 pendingConfirm
        when(eventStore.findLatest("sid-race")).thenReturn(errRow);

        var frames = tailer.tail("sid-race", null, -1).collectList().block(Duration.ofSeconds(5));

        assertNotNull(frames);
        var data = frames.stream().map(f -> f.data()).toList();
        assertTrue(data.stream().anyMatch(d -> d != null && d.contains("boom")),
            "终止事件必须送达，不能被 done 帧掩盖: " + data);
        assertTrue(data.get(data.size() - 1).contains("done"), "末帧仍应是 done: " + data);
    }

    @Test
    void concurrentViewersEachGetTheirOwnTail() {
        // 多标签页：第二个订阅者不再受 multicast 语义影响——
        // 每个订阅者独立读 DB，各自持有游标。
        when(eventStore.queryAfter(eq("sid-m"), isNull(), anyInt())).thenAnswer(inv -> {
            int cursor = inv.getArgument(2);
            if (cursor < 1) {
                return Flux.just(new SessionEventStore.EnvelopedEvent(1, "TEXT_BLOCK_DELTA",
                    "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"shared\"}", null));
            }
            if (cursor < 2) {
                return Flux.just(new SessionEventStore.EnvelopedEvent(2, "AGENT_END", "{}", null));
            }
            return Flux.empty();
        });

        var a = tailer.tail("sid-m", null, 0).collectList().block(Duration.ofSeconds(5));
        var b = tailer.tail("sid-m", null, 0).collectList().block(Duration.ofSeconds(5));

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(3, a.size(), "订阅者 A 应收到 delta + 终态事件 + done");
        assertEquals(3, b.size(), "订阅者 B 应收到 delta + 终态事件 + done");
        assertTrue(a.get(0).data().contains("shared"), "A 收到的首个事件应是 delta");
        assertTrue(b.get(0).data().contains("shared"), "B 收到的首个事件应是 delta");
    }
}
