package io.agentmanager.framework.service;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * SessionEventBus 单元测试（durable-sse-plan §5.2）。
 *
 * <p>测试要点：
 * <ul>
 *   <li>emit 广播事件给所有订阅者</li>
 *   <li>emitSynthetic 合成事件同样广播</li>
 *   <li>subscribe 回放历史 + 实时流</li>
 *   <li>closeSession 关闭后订阅者收到 onComplete</li>
 *   <li>心跳帧包含 comment "hb"</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SessionEventBusTest {

    @Mock
    private SessionEventStore eventStore;

    private SessionEventBus eventBus;

    @BeforeEach
    void setUp() {
        lenient().when(eventStore.queryAfter(any(), any(), anyInt())).thenReturn(Flux.empty());
        lenient().when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        eventBus = new SessionEventBus(eventStore,
            Duration.ofMillis(100),  // 心跳 100ms
            Duration.ofMinutes(5),   // eviction 5min
            64);
    }

    @Test
    void evictStaleSinksKeepsLiveSinksAndEvictsAbandonedOnes() {
        // 清理条件必须包含「无订阅者」，否则一次长静默（长工具调用，可达数分钟）就会
        // 把执行方自己的 sink 清掉 —— owner 的 SSE 会在 turn 中途无声结束。
        var bus = new SessionEventBus(eventStore,
            Duration.ofMillis(100),
            Duration.ZERO,   // eviction 立即到期，只留订阅者这一个判据
            64);

        var live = bus.subscribe("sid-live", 0, null).subscribe();
        var abandoned = bus.subscribe("sid-dead", 0, null).subscribe();
        abandoned.dispose();   // 客户端断开：该 sink 不再有订阅者

        assertEquals(1, bus.evictStaleSinks(), "只应清掉已无订阅者的 sink");
        live.dispose();
    }

    @Test
    void emitBroadcastsToSubscribers() {
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        var flux = eventBus.subscribe("sid-1", 0, "rid-1")
            .filter(sse -> sse.data() != null)
            .take(1)
            .timeout(Duration.ofSeconds(3));

        // StepVerifier 先订阅，然后发射事件
        StepVerifier.create(flux)
            .then(() -> eventBus.emit("sid-1", mockAgentEvent("hello"), "rid-1"))
            .expectNextMatches(sse -> sse.data() != null && sse.data().contains("TEXT_BLOCK_DELTA"))
            .verifyComplete();
    }

    @Test
    void emitSyntheticBroadcastsSynthEvents() {
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        var flux = eventBus.subscribe("sid-2", 0, "rid-2")
            .filter(sse -> sse.data() != null)
            .take(1)
            .timeout(Duration.ofSeconds(3));

        StepVerifier.create(flux)
            .then(() -> eventBus.emitSynthetic("sid-2", "rid-2", "file_ready",
                "{\"type\":\"file_ready\",\"file_name\":\"report.pdf\"}"))
            .expectNextMatches(sse -> sse.data().contains("file_ready"))
            .verifyComplete();
    }

    @Test
    void subscribeReplaysHistoryThenLive() {
        // 回放一条历史
        var historical = new SessionEventStore.EnvelopedEvent(1, "TEXT_BLOCK_DELTA",
            "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"past\"}", "rid-3");
        when(eventStore.queryAfter("sid-3", "rid-3", 0))
            .thenReturn(Flux.just(historical));
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(2);

        var flux = eventBus.subscribe("sid-3", 0, "rid-3")
            .filter(sse -> sse.data() != null)
            .take(2)
            .timeout(Duration.ofSeconds(3));

        // 延迟发射实时事件（在回放完成后）
        StepVerifier.create(flux)
            .expectNextMatches(sse -> sse.data().contains("past"))
            .thenAwait(Duration.ofMillis(200))
            .then(() -> eventBus.emitSynthetic("sid-3", "rid-3", "TEXT_BLOCK_DELTA",
                "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"live\"}"))
            .expectNextMatches(sse -> sse.data().contains("live"))
            .verifyComplete();
    }

    @Test
    void closeSessionCompletesSubscribers() {
        var flux = eventBus.subscribe("sid-4", 0, null)
            .timeout(Duration.ofSeconds(3));

        eventBus.closeSession("sid-4");

        StepVerifier.create(flux)
            .verifyComplete();
    }

    @Test
    void ensureSinkCreatesSink() {
        var sink = eventBus.ensureSink("sid-8");
        assertNotNull(sink);
        var sink2 = eventBus.ensureSink("sid-8");
        assertSame(sink, sink2);
    }

    @Test
    void evictStaleSinksCleansExpired() {
        eventBus = new SessionEventBus(eventStore,
            Duration.ofMillis(100),
            Duration.ofMillis(50),
            64);

        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        eventBus.ensureSink("sid-9");
        eventBus.emitSynthetic("sid-9", "rid-9", "ping", "{}");

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        int evicted = eventBus.evictStaleSinks();
        assertTrue(evicted >= 1);
    }

    @Test
    void closeSessionFinishesTurnOnStore() {
        eventBus.ensureSink("sid-finish");
        eventBus.closeSession("sid-finish");
        verify(eventStore).finishTurn("sid-finish");
    }

    @Test
    void abandonSessionDiscardsBufferInsteadOfFlushing() {
        // 丢租约的收尾走 abandonTurn（丢弃缓冲），**不是** finishTurn（刷缓冲）：
        // 缓冲里那些 seq 是本副本以为自己还持锁时分配的，写下去就是与新 owner 重叠。
        var flux = eventBus.subscribe("sid-abandon", 0, null)
            .timeout(Duration.ofSeconds(3));
        eventBus.ensureSink("sid-abandon");

        eventBus.abandonSession("sid-abandon");

        verify(eventStore).abandonTurn("sid-abandon");
        verify(eventStore, never()).finishTurn("sid-abandon");
        StepVerifier.create(flux).verifyComplete();   // 订阅者照样收到 onComplete
    }

    @Test
    void beginTurnSeedsSeqAndCreatesSink() {
        var sink = eventBus.beginTurn("sid-begin");
        assertNotNull(sink);
        verify(eventStore).seedSeq("sid-begin");
        assertSame(sink, eventBus.ensureSink("sid-begin"));
    }

    @Test
    void closeSessionAfterEmitStillDeliversEventToSubscriber() {
        // HITL 时序契约：先 emit(permission_ask) 再 closeSession，
        // 订阅者必须收到该事件，然后才收到 onComplete。
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        var flux = eventBus.subscribe("sid-hitl", 0, "rid-h")
            .filter(sse -> sse.data() != null)
            .take(1)
            .timeout(Duration.ofSeconds(3));

        StepVerifier.create(flux)
            .then(() -> {
                eventBus.emitSynthetic("sid-hitl", "rid-h", "permission_ask",
                    "{\"type\":\"permission_ask\"}");
                eventBus.closeSession("sid-hitl");
            })
            .expectNextMatches(sse -> sse.data().contains("permission_ask"))
            .verifyComplete();
    }

    // ===== 辅助方法 =====

    private static AgentEvent mockAgentEvent(String delta) {
        return new TextBlockDeltaEvent("bid-1", delta, "rid-1");
    }
}
