package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;

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
 * 不变量 I1 回归：DB 中 session_event 的 seq 始终是连续前缀。
 *
 * <p>这是游标回放正确性的前提——若 DB 中出现"里程碑已落库但中间 delta 缺失"，
 * 重连客户端回放会读到空洞，或把游标推进到尚未写入的 seq 之后，永久丢失中间的 delta。
 *
 * <p>破坏 I1 的回归形态是：里程碑单独先落库、缓冲中的 delta 事后回填。本用例记录的
 * seq 带**批次写入顺序**，该形态会表现为序列里先出现大的 seq 再出现小的 seq，
 * 下面的逐位连续性断言随即可捕获。
 */
@ExtendWith(MockitoExtension.class)
class SessionEventStoreBatchTest {

    @Mock private DataSource dataSource;

    private SessionEventStore store;

    /** 记录每次批量 INSERT 写入的 seq（按写入顺序），模拟 DB 中的实际内容 */
    private final List<Integer> writtenSeqs = new ArrayList<>();

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
        // 列序固定为 (session_id, seq, event_type, payload, reply_id, created_at)，
        // 每行按 setString/setInt/setString/setString/setString 绑定 5 个占位符，
        // 故 seq 只出现在 1-based 索引 2、7、12...，即唯一满足 idx % 5 == 2 的位置。
        doAnswer(inv -> {
            int idx = inv.getArgument(0);
            int value = inv.getArgument(1);
            if (idx % 5 == 2) writtenSeqs.add(value);
            return null;
        }).when(insertPs).setInt(anyInt(), anyInt());

        // flushIntervalMs 故意取 60s：时间窗在本用例执行期间绝不触发，
        // 批次边界只由 batchSize 与里程碑决定，保证断言确定性。
        store = new SessionEventStore(dataSource, 7, 3, 60_000);
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
