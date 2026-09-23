package io.agentmanager.framework.service;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.agentmanager.framework.controller.AgentEventSseSerializer;
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

    // ===== A1：replyId 注入前移写路径，落库与广播同源 =====

    @Test
    void emitPersistsAndBroadcastsTheSamePayloadString() {
        // 落库 payload == 广播 EnvelopedEvent.payload == withReplyId 产物（同一份字符串）。
        // TextBlockDeltaEvent 的 payload 自带 replyId（extractReplyId 覆盖）→ 注入短路，
        // 期望值 = serializer 原样输出
        var appended = new java.util.ArrayList<String>();
        when(eventStore.append(anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(inv -> {
                appended.add(inv.getArgument(3));
                return 7;
            });

        var flux = eventBus.subscribe("sid-a1", 0, "rid-a1")
            .filter(sse -> sse.data() != null)
            .take(1)
            .timeout(Duration.ofSeconds(3));

        var event = mockAgentEvent("hi");
        String expected = AgentEventSseSerializer.withReplyId(
            AgentEventSseSerializer.payload(event), "rid-a1");
        StepVerifier.create(flux)
            .then(() -> eventBus.emit("sid-a1", event, "rid-a1"))
            .expectNextMatches(sse ->
                expected.equals(sse.data())
                    && expected.equals(appended.get(0))   // 落库的就是广播的
                    && "7".equals(sse.id()))
            .verifyComplete();
    }

    @Test
    void emitSyntheticPersistsAndBroadcastsInjectedPayload() {
        // 合成帧（file_ready 等 extractReplyId 未覆盖的类型）收口前落库无 replyId、读端现场注入；
        // A1 后注入发生在写路径：落库 p 与广播 data 均为注入后形态，replyId 在 JSON 末尾
        var appended = new java.util.ArrayList<String>();
        when(eventStore.append(anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(inv -> {
                appended.add(inv.getArgument(3));
                return 3;
            });

        var flux = eventBus.subscribe("sid-a2", 0, "rid-a2")
            .filter(sse -> sse.data() != null)
            .take(1)
            .timeout(Duration.ofSeconds(3));

        var raw = "{\"type\":\"file_ready\",\"file_name\":\"a.pdf\"}";
        StepVerifier.create(flux)
            .then(() -> eventBus.emitSynthetic("sid-a2", "rid-a2", "file_ready", raw))
            .expectNextMatches(sse -> {
                String expected = AgentEventSseSerializer.withReplyId(raw, "rid-a2");
                return sse.data().equals(appended.get(0))
                    && sse.data().equals(expected)
                    && sse.data().endsWith("\"replyId\":\"rid-a2\"}");
            })
            .verifyComplete();
    }

    @Test
    void subscribeServesLegacyAndNewRowsWithIdenticalBytes() {
        // 升级前落库的行（p 内无 replyId）与升级后的行（p 已含 replyId）可能混在同一个
        // Stream 里（同一 session 跨升级回放）：读端按「有无 replyId」各自走对应分支，
        // 输出都必须与收口前读端算法一致
        var legacyRow = new SessionEventStore.EnvelopedEvent(1, "permission_ask",
            "{\"type\":\"permission_ask\",\"reply_id\":\"rid-mix\"}", "rid-mix");
        var newRow = new SessionEventStore.EnvelopedEvent(2, "AGENT_END",
            "{\"type\":\"AGENT_END\",\"replyId\":\"rid-mix\"}", "rid-mix");
        when(eventStore.queryAfter("sid-mix", "rid-mix", 0))
            .thenReturn(Flux.just(legacyRow, newRow));

        var frames = eventBus.subscribe("sid-mix", 0, "rid-mix")
            .filter(sse -> sse.data() != null)
            .take(2)
            .collectList()
            .block(Duration.ofSeconds(3));

        assertEquals(2, frames.size());
        assertEquals(legacyInject(legacyRow.payload(), legacyRow.replyId()), frames.get(0).data(),
            "存量行：读端兜底注入，输出与收口前读端算法逐字节一致");
        assertEquals(legacyInject(newRow.payload(), newRow.replyId()), frames.get(1).data(),
            "新形态行：has(replyId) 短路透传");
        assertEquals("1", frames.get(0).id());
        assertEquals("2", frames.get(1).id());
    }

    /**
     * 参照实现：收口前 SessionEventBus 私有 toSSE 的注入算法原样拷贝（oracle）。
     */
    private static String legacyInject(String payload, String replyId) {
        if (replyId == null || replyId.isBlank()) {
            return payload;
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            var node = mapper.readTree(payload);
            if (node != null && node.isObject() && !node.has("replyId")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("replyId", replyId);
                return mapper.writeValueAsString(node);
            }
            return payload;
        } catch (Exception e) {
            return payload;
        }
    }

    // ===== A3：持久化失败（append 返回 -1）不广播 =====

    @Test
    void emitDoesNotBroadcastWhenPersistFails() {
        // append -1 = 事件永不落库；广播它会产生一条回放（断连续传、重放）永远
        // 补不出来的实时帧。心跳间隔放大到 60s，让 expectNoEvent 只盯业务帧
        var quietBus = new SessionEventBus(eventStore,
            Duration.ofSeconds(60), Duration.ofMinutes(5), 64);
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(-1);

        var flux = quietBus.subscribe("sid-f1", 0, "rid-f1")
            .filter(sse -> sse.data() != null);

        StepVerifier.create(flux)
            .then(() -> eventBus.emit("sid-f1", mockAgentEvent("lost"), "rid-f1"))
            .expectNoEvent(Duration.ofMillis(600))
            .thenCancel()
            .verify();
    }

    @Test
    void emitSyntheticDoesNotBroadcastWhenPersistFails() {
        var quietBus = new SessionEventBus(eventStore,
            Duration.ofSeconds(60), Duration.ofMinutes(5), 64);
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(-1);

        var flux = quietBus.subscribe("sid-f2", 0, "rid-f2")
            .filter(sse -> sse.data() != null);

        StepVerifier.create(flux)
            .then(() -> eventBus.emitSynthetic("sid-f2", "rid-f2", "file_ready", "{\"type\":\"file_ready\"}"))
            .expectNoEvent(Duration.ofMillis(600))
            .thenCancel()
            .verify();
    }

    @Test
    void emitStillReturnsMinusOneAndTouchesActiveWhenPersistFails() {
        // 返回值 -1 与 touchActive 语义不变：调用方均不消费返回值、收尾路径照常；
        // touch 经 evictStaleSinks 间接断言——没 touch 过的 session 不会被清理
        var bus = new SessionEventBus(eventStore,
            Duration.ofSeconds(60), Duration.ZERO, 64);   // eviction 立即到期
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(-1);

        var sub = bus.subscribe("sid-touch", 0, "rid-touch").subscribe();
        int rc = bus.emit("sid-touch", mockAgentEvent("lost"), "rid-touch");
        assertEquals(-1, rc, "emit 返回值仍为 -1（持久化失败）");
        sub.dispose();

        try { Thread.sleep(100); } catch (InterruptedException ignored) { }

        assertEquals(1, bus.evictStaleSinks(),
            "emit 失败路径仍应 touchActive（否则该 sink 不会进入过期清理）");
    }
}
