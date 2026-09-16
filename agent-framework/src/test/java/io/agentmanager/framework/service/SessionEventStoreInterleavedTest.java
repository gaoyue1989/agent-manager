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
 * 不变量 I1 在**多 session 交错**下的回归（补充 SessionEventStoreBatchTest 的单 session 覆盖）。
 *
 * <p>缓冲按 session 分区（而不是一条全局 FIFO），钉住三条性质：
 * <ol>
 *   <li>{@code finishTurn(A)} 只刷 A 的行，B 的缓冲原封不动；
 *   <li>按 session 看，落库的 seq 顺序与完整性不受其他 session 干扰；
 *   <li><b>一次 {@code appendBatch} 只装一个 session 的行</b>——所以 A 的批被整批拒绝时
 *       B 的行不受牵连。
 * </ol>
 *
 * <p>第三条是分区的主要收益：Redis 没有跨批回滚，一批里混着多个 session 时，一次
 * XADD 被拒（非单调 ID）会把邻居的行一并丢掉——那是把「静默重复」换成「静默丢失 + 殃及
 * 邻居」，比原问题更糟。分区之后一条批只装一个 session 的行，影响面止于肇事 session。
 *
 * <p>批大小设为 10、时间窗 60s，确保测试期间不会因"满批/超时"而刷出——唯一的刷出点是
 * 里程碑事件与 {@code finishTurn}。
 *
 * <p>流顶端（{@code tailSeq}）在本用例里固定桩为 0：只有固定桩才能把「计数器已释放 →
 * 从流顶端重新播种」与「计数器仍在 → 接着上一个 seq 发」区分开（前者得 1，后者得 3）。
 */
@ExtendWith(MockitoExtension.class)
class SessionEventStoreInterleavedTest {

    private RedisEventLog eventLog;

    private SessionEventStore store;

    /** 成功写入流的 (sessionId, seq)，模拟流中实际存在的事件 */
    private final List<String> written = new ArrayList<>();

    /** 让某个 session 的批被整批拒绝（模拟 XADD 非单调 ID 被 Redis 拒绝）；null = 不失败 */
    private volatile String rejectBatchFor;

    @BeforeEach
    void setUp() {
        eventLog = mock(RedisEventLog.class);
        when(eventLog.tailSeq(anyString())).thenReturn(0);
        // 每次刷出都是一次独立的 appendBatch 调用，两侧状态天然隔离。
        // 行只有**刷出成功**才计入 written：被拒时整批没有进流——若在调用前就记进 written，
        // 下面的断言会看到幻影行。
        when(eventLog.appendBatch(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            String sid = inv.getArgument(0);
            List<RedisEventLog.Entry> entries = inv.getArgument(1);
            if (sid.equals(rejectBatchFor)) {
                return -1;   // 整批被 Redis 拒绝
            }
            for (var e : entries) {
                written.add(sid + ":" + e.seq());
            }
            return entries.size();
        });

        store = new SessionEventStore(eventLog, 7, 10, 60_000);
    }

    @Test
    void finishTurnFlushesOnlyThatSessionsRows() {
        store.seedSeq("A");
        store.seedSeq("B");

        // 交错写入：A、B 各 2 条，均留在缓冲中（批大小 10、时间窗 60s）
        store.append("A", "ra", "TEXT_BLOCK_DELTA", "{\"d\":\"a1\"}");
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b1\"}");
        store.append("A", "ra", "TEXT_BLOCK_DELTA", "{\"d\":\"a2\"}");
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b2\"}");
        assertEquals(List.of(), written, "未满批不应落库");

        store.finishTurn("A");

        assertEquals(List.of("A:1", "A:2"), written,
            "finishTurn(A) 只应刷出 A 的行，不得捎带 B 的");
        assertEquals(List.of("1", "2"), seqsOf("A"), "A 的 seq 必须是连续前缀");

        // B 的行原封不动地留在缓冲里：既没被 A 的收尾刷出，也没被丢掉
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b3\"}");
        assertEquals(List.of("A:1", "A:2"), written, "B 的缓冲不该被 A 的收尾影响");

        store.finishTurn("B");

        assertEquals(List.of("A:1", "A:2", "B:1", "B:2", "B:3"), written,
            "B 随后按序整体刷出，不漏不重");
        assertEquals(List.of("1", "2", "3"), seqsOf("B"), "B 的 seq 必须是连续前缀");

        // A 的计数器已释放（收尾时缓冲为空）→ 新 turn 重新从流顶端播种（此处流顶端桩恒为 0）。
        // 里程碑事件会刷出 A 的缓冲，但同样只刷 A 的
        store.seedSeq("A");
        store.append("A", "ra2", "AGENT_END", "{}");
        assertEquals(List.of("1", "2", "1"), seqsOf("A"),
            "计数器已释放，A 的新 turn 从流顶端重新播种");
        assertEquals(List.of("A:1", "A:2", "B:1", "B:2", "B:3", "A:1"), written,
            "A 的里程碑只刷 A 自己的行");
    }

    @Test
    void collidingSessionDoesNotDragDownOtherSessionsRows() {
        // 分区之前这里是一条全局缓冲：A 撞唯一键会让整批回滚，B 的行跟着一起没了。
        // Redis 版没有「唯一键」，等价物是 XADD 对非单调 ID 的原子拒绝（appendBatch 返回 -1）；
        // 分区之后一次调用只装一个 session 的行，失败的影响面止于肇事 session。
        store.seedSeq("A");
        store.seedSeq("B");
        store.append("A", "ra", "TEXT_BLOCK_DELTA", "{\"d\":\"a1\"}");
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b1\"}");

        rejectBatchFor = "A";

        store.finishTurn("A");   // A 的批被整批拒绝 → 该批的行被丢弃（已知偏差）
        store.finishTurn("B");   // B 的批在自己的 appendBatch 调用里，必须照常写入

        assertEquals(List.of("B:1"), written,
            "A 的批被拒不得带走 B 的行");
        verify(eventLog, times(1)).appendBatch(eq("A"), anyList(), anyInt());

        // 失败批的行已随缓冲一起被移除：不会在后续某次收尾时"复活"并写到新 owner
        // 已占用的 seq 区间上。丢弃是确定的，不取决于后续还有没有事件进来。
        store.finishTurn("A");
        assertEquals(List.of("B:1"), written, "失败批不得在后续收尾时被重试写出");
        verify(eventLog, times(1)).appendBatch(eq("A"), anyList(), anyInt());
    }

    private List<String> seqsOf(String sid) {
        return written.stream().filter(s -> s.startsWith(sid + ":"))
            .map(s -> s.split(":")[1]).toList();
    }
}
