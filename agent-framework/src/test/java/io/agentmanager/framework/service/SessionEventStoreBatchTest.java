package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 不变量 I1 回归：流中 session_event 的 seq 始终是连续前缀。
 *
 * <p>这是游标回放正确性的前提——若流中出现"里程碑已落库但中间 delta 缺失"，
 * 重连客户端回放会读到空洞，或把游标推进到尚未写入的 seq 之后，永久丢失中间的 delta。
 *
 * <p>破坏 I1 的回归形态是：里程碑单独先落库、缓冲中的 delta 事后回填。本用例记录的
 * seq 带**批次写入顺序**，该形态会表现为序列里先出现大的 seq 再出现小的 seq，
 * 下面的逐位连续性断言随即可捕获。
 *
 * <p>存储换成 Redis Streams 之后，一批 = 一次 {@link RedisEventLog#appendBatch}，
 * 「顺序」的观测点从 INSERT 语句的两个分组变成一次调用的 entry 列表。
 */
@ExtendWith(MockitoExtension.class)
class SessionEventStoreBatchTest {

    private RedisEventLog eventLog;

    private SessionEventStore store;

    /** 记录每次刷出写入的 seq（按写入顺序），模拟流中的实际内容 */
    private final List<Integer> writtenSeqs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        eventLog = mock(RedisEventLog.class);
        // 流顶端桩恒为 0：本用例只关心攒批边界，seq 从 1 开始最好读
        when(eventLog.tailSeq(anyString())).thenReturn(0);
        when(eventLog.appendBatch(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<RedisEventLog.Entry> entries = inv.getArgument(1);
            for (var e : entries) {
                writtenSeqs.add(e.seq());
            }
            return entries.size();
        });

        // flushIntervalMs 故意取 60s：时间窗在本用例执行期间绝不触发，
        // 批次边界只由 batchSize 与里程碑决定，保证断言确定性。
        store = new SessionEventStore(eventLog, 7, 3, 60_000);
    }

    @Test
    void seqRemainsContiguousPrefixWithDeltaBuffering() {
        store.seedSeq("sid-i1");

        // 5 条 delta：批量大小为 3 → 前 3 条触发一次落库，后 2 条留缓冲
        for (int i = 0; i < 5; i++) {
            store.append("sid-i1", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"x\"}");
        }
        assertEquals(List.of(1, 2, 3), writtenSeqs, "缓冲未满部分不应落库");

        // 里程碑：缓冲（4,5）与本行（6）一并落库 → 前缀保持连续
        store.append("sid-i1", "r", "AGENT_END", "{}");
        assertEquals(List.of(1, 2, 3, 4, 5, 6), writtenSeqs);

        // 断言前缀连续
        for (int i = 0; i < writtenSeqs.size(); i++) {
            assertEquals(i + 1, writtenSeqs.get(i), "seq 在位置 " + i + " 处不连续: " + writtenSeqs);
        }
    }

    @Test
    void finishTurnFlushesTailSoPrefixStaysContiguous() {
        store.seedSeq("sid-i2");
        store.append("sid-i2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"a\"}");
        store.append("sid-i2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"b\"}");
        assertEquals(List.of(), writtenSeqs, "缓冲未满不应落库");

        store.finishTurn("sid-i2");
        assertEquals(List.of(1, 2), writtenSeqs);
    }
}
