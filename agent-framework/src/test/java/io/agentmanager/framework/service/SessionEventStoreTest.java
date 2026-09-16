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
    void appendReturnsSeqOnSuccess() throws Exception {
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

        int seq = store.append("sid-1", "rid-1", "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}");
        assertEquals(6, seq);
    }

    @Test
    void appendReturnsMinusOneOnFailure() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("connection failed"));

        int seq = store.append("sid-1", "rid-1", "TEXT_BLOCK_DELTA", "{}");
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
}
