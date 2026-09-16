package io.agentmanager.framework.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

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
 * SessionEventStore 单元测试（durable-sse-plan §5.2）。
 *
 * <p>测试要点：
 * <ul>
 *   <li>append 正常写入并返回递增 seq</li>
 *   <li>append 失败返回 -1 但不抛异常（降级语义）</li>
 *   <li>queryAfter 按游标回放增量事件</li>
 *   <li>findLatest / findMaxSeq 正确返回最新状态</li>
 *   <li>deleteBefore 按时间清理过期记录</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SessionEventStoreTest {

    @Mock
    private DataSource dataSource;

    private SessionEventStore store;

    @BeforeEach
    void setUp() {
        // 不真正连 DB，用 spy 测试非 DB 逻辑
        // 注意：initSchema 会在构造器里调用——需要 mock Connection
        try {
            var conn = mock(java.sql.Connection.class);
            var stmt = mock(java.sql.Statement.class);
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(stmt);
            when(stmt.executeUpdate(anyString())).thenReturn(0);
            store = new SessionEventStore(dataSource, 7);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void retentionDays() {
        assertEquals(7, store.retentionDays());
    }

    @Test
    void abandonTurnDiscardsBufferedRowsWithoutFlushing() throws Exception {
        // 丢租约的收尾**不能**刷缓冲：缓冲里那些行的 seq 是本副本「以为自己还持锁」时
        // 分配的，此刻新 owner 可能已在同一区间分配过 seq —— 写下去正是 C1 要防的静默重复行。
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        when(conn.prepareStatement(contains("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.append("sid-ab", "rid-1", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");
        // 另一个 session 的行也在缓冲里：A 的收尾**不得**把它顺带刷出去
        // （这正是「abandonTurn 不是 finishTurn」的可观测差别）
        store.append("sid-other", "rid-2", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");
        store.append("sid-ab", "rid-1", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");

        store.abandonTurn("sid-ab");

        verify(insertPs, never()).executeUpdate();
        assertEquals(1, store.append("sid-ab", "rid-1", "AGENT_END", "{}"),
            "计数器已释放 → 下一次 append 重新播种（DB 最大值 0 + 1）");
    }

    @Test
    void appendReturnsSeqOnSuccess() throws Exception {
        // 用里程碑事件（非 delta）——delta 现在只进缓冲、不落库，见 deltasAreBufferedUntilMilestone
        // Mock: SELECT MAX → 5, INSERT ok
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(5);
        when(conn.prepareStatement(contains("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        int seq = store.append("sid-1", "rid-1", "TOOL_CALL_START", "{\"type\":\"TOOL_CALL_START\"}");
        assertEquals(6, seq);
    }

    @Test
    void appendReturnsMinusOneOnFailure() throws Exception {
        // 用里程碑事件：delta 的落库是延迟的，失败语义只在 flush 时体现
        when(dataSource.getConnection()).thenThrow(new RuntimeException("connection failed"));

        int seq = store.append("sid-1", "rid-1", "TOOL_CALL_START", "{}");
        assertEquals(-1, seq);
    }

    @Test
    void seedSeqThenAppendDoesNotQueryMaxAgain() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(5);
        when(conn.prepareStatement(contains("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.seedSeq("sid-counter");
        assertEquals(6, store.append("sid-counter", "rid-1", "TOOL_CALL_START", "{}"));
        assertEquals(7, store.append("sid-counter", "rid-1", "TOOL_CALL_START", "{}"));

        // SELECT MAX 只应在 seedSeq 时执行一次
        verify(conn, times(1)).prepareStatement(contains("SELECT COALESCE(MAX"));
    }

    @Test
    void seedSeqIsIdempotent() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(5);
        when(conn.prepareStatement(contains("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.seedSeq("sid-idem");
        store.append("sid-idem", "rid-1", "TOOL_CALL_START", "{}");   // seq=6
        store.seedSeq("sid-idem");                                     // 重复播种不得回退
        assertEquals(7, store.append("sid-idem", "rid-1", "TOOL_CALL_START", "{}"));

        verify(conn, times(1)).prepareStatement(contains("SELECT COALESCE(MAX"));
    }

    @Test
    void appendFallsBackToDbWhenNotSeeded() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(41);
        when(conn.prepareStatement(contains("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        // 未播种：退回 DB 查询，保持既有语义
        assertEquals(42, store.append("sid-unseeded", "rid-1", "TOOL_CALL_START", "{}"));
    }

    @Test
    void queryAfterReturnsFlux() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        // 两条结果
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getInt("seq")).thenReturn(3, 4);
        when(rs.getString("event_type")).thenReturn("TEXT_BLOCK_DELTA", "AGENT_END");
        when(rs.getString("payload")).thenReturn("{\"type\":\"TEXT_BLOCK_DELTA\"}", "{\"type\":\"AGENT_END\"}");
        when(rs.getString("reply_id")).thenReturn("rid-1", "rid-1");

        var flux = store.queryAfter("sid-1", "rid-1", 2);
        StepVerifier.create(flux)
            .expectNextMatches(e -> e.seq() == 3 && "TEXT_BLOCK_DELTA".equals(e.type()))
            .expectNextMatches(e -> e.seq() == 4 && "AGENT_END".equals(e.type()))
            .verifyComplete();
    }

    @Test
    void queryAfterReleasesConnectionBeforeEmitting() throws Exception {
        // 用独立连接 mock——setUp 里的 conn 已被 initSchema 关闭过一次，
        // 复用会让 verify(conn).close() 的计数失真
        var queryConn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(queryConn);
        when(queryConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getInt("seq")).thenReturn(3);
        when(rs.getString("event_type")).thenReturn("TEXT_BLOCK_DELTA");
        when(rs.getString("payload")).thenReturn("{}");
        when(rs.getString("reply_id")).thenReturn("rid-1");

        var flux = store.queryAfter("sid-conn", "rid-1", 2);
        StepVerifier.create(flux)
            .expectNextMatches(e -> e.seq() == 3)
            .verifyComplete();

        // 关键：ResultSet / Statement / Connection 在事件被消费前就已关闭
        verify(rs).close();
        verify(ps).close();
        verify(queryConn).close();
    }

    @Test
    void queryAfterPagesThroughMultipleBatches() throws Exception {
        var queryConn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(queryConn);
        when(queryConn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);

        int pageSize = SessionEventStore.queryPageSize();
        // 第 1 页返回满页（pageSize 行）→ 触发第 2 次查询；第 2 页返回 0 行 → 结束
        var remaining = new java.util.concurrent.atomic.AtomicInteger(pageSize);
        when(rs.next()).thenAnswer(inv -> remaining.getAndDecrement() > 0);
        var seqs = new java.util.ArrayDeque<Integer>();
        for (int i = 1; i <= pageSize; i++) seqs.add(i);
        when(rs.getInt("seq")).thenAnswer(inv -> seqs.isEmpty() ? 1 : seqs.poll());
        when(rs.getString("event_type")).thenReturn("TEXT_BLOCK_DELTA");
        when(rs.getString("payload")).thenReturn("{}");
        when(rs.getString("reply_id")).thenReturn("rid-1");

        StepVerifier.create(store.queryAfter("sid-page", "rid-1", 0))
            .expectNextCount(pageSize)
            .verifyComplete();

        // 满页后应再发起一次查询（共 2 次）
        verify(queryConn, times(2)).prepareStatement(anyString());
    }

    @Test
    void queryAfterErrorsWhenPageQueryFails() throws Exception {
        var queryConn = mock(java.sql.Connection.class);
        when(dataSource.getConnection()).thenReturn(queryConn);
        when(queryConn.prepareStatement(anyString()))
            .thenThrow(new java.sql.SQLException("db down"));

        // 回放中途查询失败必须是 error，不能静默 complete——
        // 否则重连的客户端会拿到被截断的回放却以为自己已追平
        StepVerifier.create(store.queryAfter("sid-err", "rid-1", 0))
            .expectError(java.sql.SQLException.class)
            .verify();
    }

    @Test
    void findLatestReturnsNullWhenEmpty() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertNull(store.findLatest("sid-empty"));
    }

    @Test
    void findMaxSeqReturnsZeroWhenEmpty() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);

        assertEquals(0, store.findMaxSeq("sid-empty"));
    }

    @Test
    void deleteBeforeReturnsCount() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var ps = mock(java.sql.PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(5);

        int deleted = store.deleteBefore(Instant.now().minus(Duration.ofDays(7)));
        assertEquals(5, deleted);
    }

    @Test
    void envelopedEventRecord() {
        var e = new SessionEventStore.EnvelopedEvent(1, "TEXT_BLOCK_DELTA", "{\"delta\":\"hello\"}", "rid-1");
        assertEquals(1, e.seq());
        assertEquals("TEXT_BLOCK_DELTA", e.type());
        assertEquals("{\"delta\":\"hello\"}", e.payload());
        assertEquals("rid-1", e.replyId());
    }

    @Test
    void deltasAreBufferedUntilMilestone() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        // lenient：本用例期望它**不被调用**。保留 stub 是为了让"提前落库"这种实现错误
        // 表现为断言失败，而不是 mock 默认返回 null → NPE 被 insertBatch 吞掉后静默通过。
        lenient().when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);

        store.seedSeq("sid-buf");
        store.append("sid-buf", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"a\"}");
        store.append("sid-buf", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"b\"}");

        // 纯 delta：连 INSERT 语句都不准备，更不落库
        verify(conn, never()).prepareStatement(startsWith("INSERT INTO session_event"));
        verify(insertPs, never()).executeUpdate();
    }

    @Test
    void milestoneFlushesBufferedDeltasInSameStatement() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.seedSeq("sid-buf2");
        store.append("sid-buf2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"a\"}");
        store.append("sid-buf2", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"b\"}");
        store.append("sid-buf2", "r", "AGENT_END", "{}");

        // 捕获所有 prepareStatement 调用（seedSeq 会先发一次 SELECT MAX），取最后一次即 INSERT
        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.startsWith("INSERT INTO session_event"), "实际 SQL: " + sql);
        // 3 行数据 → 3 组 (?,?,?,?,?,NOW(3))；每行 6 个 ?，故按 NOW(3) 计数
        long groups = sql.split("NOW\\(3\\)", -1).length - 1;
        assertEquals(3, groups, "期望 3 组 VALUES，实际 SQL: " + sql);
        verify(insertPs, times(1)).executeUpdate();
    }

    @Test
    void finishTurnFlushesPendingRows() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var selectPs = mock(java.sql.PreparedStatement.class);
        var insertPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenReturn(insertPs);
        when(insertPs.executeUpdate()).thenReturn(1);

        store.seedSeq("sid-fin");
        store.append("sid-fin", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"tail\"}");
        store.finishTurn("sid-fin");

        verify(insertPs, times(1)).executeUpdate();

        // 计数器已释放：下一次 append 退回 DB 查询（SELECT MAX 再执行一次）
        store.append("sid-fin", "r", "TEXT_BLOCK_DELTA", "{\"delta\":\"later\"}");
        verify(conn, times(2)).prepareStatement(contains("SELECT COALESCE(MAX"));
    }
}
