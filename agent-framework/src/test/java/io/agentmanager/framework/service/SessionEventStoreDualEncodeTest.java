package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.SessionEventStore.EnvelopedEvent;

/**
 * 双编码存储语义单测（agui-native-parity-design §4 D2）：pa 随信封走攒批/刷出全链路，
 * 攒批判定仍只看 native 类型名（pa 的有无不改变刷出时机）。
 */
class SessionEventStoreDualEncodeTest {

    /** mock 底层日志：记录 appendBatch 收到的批（不真写 Redis） */
    @SuppressWarnings("unchecked")
    private static RedisEventLog recordingLog(AtomicReference<List<RedisEventLog.Entry>> captured) {
        var log = mock(RedisEventLog.class);
        when(log.appendBatch(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            captured.set((List<RedisEventLog.Entry>) inv.getArgument(1));
            return ((List<RedisEventLog.Entry>) inv.getArgument(1)).size();
        });
        when(log.tailSeq(anyString())).thenReturn(0);
        return log;
    }

    @Test
    void milestoneEntryCarriesOptionalAguiPayload() {
        var captured = new AtomicReference<List<RedisEventLog.Entry>>();
        var store = new SessionEventStore(recordingLog(captured));
        int seq = store.append("s1", "rid", "AGENT_START", "{\"type\":\"AGENT_START\"}",
            "[{\"type\":\"RUN_STARTED\"}]");
        assertEquals(1, seq);
        assertNotNull(captured.get());
        assertEquals(1, captured.get().size());
        assertEquals("[{\"type\":\"RUN_STARTED\"}]", captured.get().get(0).aguiPayload());
        assertNotNull(captured.get().get(0).payload());
    }

    @Test
    void nativeOnlyRowHasNullAguiPayload() {
        var captured = new AtomicReference<List<RedisEventLog.Entry>>();
        var store = new SessionEventStore(recordingLog(captured));
        store.append("s1", "rid", "AGENT_START", "{\"type\":\"AGENT_START\"}");
        assertNull(captured.get().get(0).aguiPayload());
    }

    @Test
    void batchingStillKeyedByNativeTypeName() {
        var captured = new AtomicReference<List<RedisEventLog.Entry>>();
        var store = new SessionEventStore(recordingLog(captured), 7, 200, 60_000);
        // 连续 delta 不触发刷出（攒批语义只看 native 后缀，pa 的有无不改变刷出时机）
        store.append("s1", "rid", "TEXT_BLOCK_DELTA", "{}", "[{\"type\":\"TEXT_MESSAGE_CONTENT\"}]");
        store.append("s1", "rid", "TEXT_BLOCK_DELTA", "{}", "[{\"type\":\"TEXT_MESSAGE_CONTENT\"}]");
        assertNull(captured.get(), "delta 攒批不应被 pa 触发刷出");
        // 里程碑触发整批同管道刷出：delta 与里程碑一起，pa 成对随行
        store.append("s1", "rid", "AGENT_END", "{}", "[{\"type\":\"RUN_FINISHED\"}]");
        assertEquals(3, captured.get().size());
        assertEquals("AGENT_END", captured.get().get(2).type());
        assertEquals("[{\"type\":\"RUN_FINISHED\"}]", captured.get().get(2).aguiPayload());
    }

    @Test
    void envelopedEventCompatConstructorKeepsNullPa() {
        var e = new EnvelopedEvent(3, "AGENT_END", "{}", "rid");
        assertNull(e.aguiPayload());
        var e2 = new EnvelopedEvent(4, "AGENT_END", "{}", "rid", "[{}]");
        assertEquals("[{}]", e2.aguiPayload());
    }

    @Test
    void fiveArgAppendWithNullPaMatchesNativeOnlyStubbing() {
        // emitCore/emitSyntheticAgui 传 null pa 时走 5 参重载——此处固化行为锚点，
        // 防回退 4 参（存量测试桩会失配）
        var captured = new AtomicReference<List<RedisEventLog.Entry>>();
        var log = mock(RedisEventLog.class);
        when(log.appendBatch(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            captured.set(inv.getArgument(1));
            return 1;
        });
        when(log.tailSeq(anyString())).thenReturn(0);
        var store = new SessionEventStore(log);
        store.append("s1", "rid", "AGENT_END", "{}", (String) null);
        assertNull(captured.get().get(0).aguiPayload());
    }
}
