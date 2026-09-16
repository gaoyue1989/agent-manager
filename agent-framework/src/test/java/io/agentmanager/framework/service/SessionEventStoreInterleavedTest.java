package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 不变量 I1 在**多 session 交错**下的回归（补充 SessionEventStoreBatchTest 的单 session 覆盖）。
 *
 * <p>{@code pending} 是全局共享的 FIFO，不是 per-session 的。多条 session 的 delta 会混在
 * 同一个缓冲里，而 {@code flushPending()} 取出的是全局缓冲的一个前缀。因此需要锁定这条性质：
 * **按 session 看，落库的 seq 顺序与完整性不受其他 session 干扰。**
 *
 * <p>这层保障脆弱且不显眼——它依赖「flush 一律取全局缓冲的整体前缀」这一个实现细节。任何
 * 「只刷某个 session 的行」的优化都会打破它，并让交错中的某个 session 出现 seq 空洞。
 *
 * <p>批大小设为 10，确保测试期间不会因"满批"而刷出——唯一的刷出点是 {@code finishTurn}。
 */
@ExtendWith(MockitoExtension.class)
class SessionEventStoreInterleavedTest {

    @Mock private DataSource dataSource;

    private SessionEventStore store;

    /** 每次批量 INSERT 写入的 (sessionId, seq)，模拟 DB 中实际落下的内容 */
    private final List<String> written = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var stmt = mock(java.sql.Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeUpdate(anyString())).thenReturn(0);

        var selectPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);

        var insertPs = mock(java.sql.PreparedStatement.class);
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        // insertBatch 的绑定顺序为 sid(setString,1), seq(setInt,2), type, payload, replyId，
        // 每行推进 5 个下标。故第 n 行的 seq 下标 = 5n-3，其前一个下标 5n-4 正是该行的 sid。
        Map<Integer, String> sidByIndex = new HashMap<>();
        doAnswer(inv -> {
            sidByIndex.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(insertPs).setString(anyInt(), anyString());
        doAnswer(inv -> {
            int seqIdx = inv.getArgument(0);
            written.add(sidByIndex.get(seqIdx - 1) + ":" + inv.getArgument(1));
            return null;
        }).when(insertPs).setInt(anyInt(), anyInt());

        store = new SessionEventStore(dataSource, 7, 10, 60_000);
    }

    @Test
    void interleavedSessionsKeepPerSessionContiguousPrefix() {
        store.seedSeq("A");
        store.seedSeq("B");

        // 交错写入：A、B 各 2 条，均留在缓冲中（批大小 10）
        store.append("A", "ra", "TEXT_BLOCK_DELTA", "{\"d\":\"a1\"}");
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b1\"}");
        store.append("A", "ra", "TEXT_BLOCK_DELTA", "{\"d\":\"a2\"}");
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b2\"}");
        assertEquals(List.of(), written, "未满批不应落库");

        // 结束 A：flushPending 刷出的是全局缓冲，B 的行会被一并写掉
        store.finishTurn("A");

        assertEquals(List.of("A:1", "B:1", "A:2", "B:2"), written,
            "全局缓冲应按 FIFO 整体刷出，不漏不重");
        assertEquals(List.of("1", "2"), seqsOf("A"), "A 的 seq 必须是连续前缀");
        assertEquals(List.of("1", "2"), seqsOf("B"), "B 被顺带刷出时也必须保序");

        // finishTurn(A) 只释放 A 的计数器：B 的必须还在，继续追加不得回退或跳号
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b3\"}");
        store.finishTurn("B");
        assertEquals(List.of("1", "2", "3"), seqsOf("B"));

        // A 的计数器已释放 → 新 turn 重新从 DB 最大值播种（此处 DB 桩恒为 0）
        store.seedSeq("A");
        store.append("A", "ra2", "AGENT_END", "{}");
        store.finishTurn("A");
        assertEquals(List.of("1", "2", "1"), seqsOf("A"),
            "计数器已释放，A 的新 turn 从 DB 最大值重新播种");
    }

    private List<String> seqsOf(String sid) {
        return written.stream().filter(s -> s.startsWith(sid + ":"))
            .map(s -> s.split(":")[1]).toList();
    }
}
