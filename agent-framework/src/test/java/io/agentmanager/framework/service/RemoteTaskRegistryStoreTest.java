package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RemoteTaskRegistryStore 单测（设计 §18.2）：INSERT IGNORE 登记、claimWake CAS
 * 真值语义、endpoint 快照查询、终态清理——mock JDBC 三件套，断言 SQL 形态与参数绑定
 * （风格同 ConfirmContextStoreMultiKeyTest）。失败路径 fail-soft（不抛出）是本 store
 * 的关键契约：登记/收口失败绝不打断父流。
 */
class RemoteTaskRegistryStoreTest {

    private DataSource dataSource;
    private Connection conn;
    private PreparedStatement ps;
    private RemoteTaskRegistryStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        store = new RemoteTaskRegistryStore(dataSource);
    }

    // ===== 登记 =====

    @Test
    void registerShouldInsertIgnoreWithAllFields() throws Exception {
        store.register("sid-1", "t-1", "booking", "http://booking:8100");

        var sql = capturedSql();
        assertTrue(sql.contains("INSERT IGNORE"), "必须幂等登记（uk 对撞静默）: " + sql);
        verify(ps).setString(1, "sid-1");
        verify(ps).setString(2, "t-1");
        verify(ps).setString(3, "booking");
        verify(ps).setString(4, "http://booking:8100");
    }

    @Test
    void registerFailureShouldNotThrow() throws Exception {
        when(ps.executeUpdate()).thenThrow(new java.sql.SQLException("db down"));
        store.register("sid-1", "t-1", "booking", "http://booking:8100");
        // 不抛出即通过：登记失败不影响父流（fail-soft 契约）
    }

    // ===== 重建扫描 =====

    @Test
    void findInFlightShouldBindStatusAndLimit() throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true).thenReturn(false);
        when(rs.getString("session_id")).thenReturn("sid-1");
        when(rs.getString("task_id")).thenReturn("t-1");
        when(rs.getString("service")).thenReturn("booking");
        when(rs.getString("endpoint")).thenReturn("http://booking:8100");
        when(rs.getString("status")).thenReturn("IN_FLIGHT");
        when(ps.executeQuery()).thenReturn(rs);

        var rows = store.findInFlight(200);

        assertEquals(1, rows.size());
        assertEquals("t-1", rows.get(0).taskId());
        assertEquals("http://booking:8100", rows.get(0).endpoint());
        verify(ps).setString(1, "IN_FLIGHT");
        verify(ps).setInt(2, 200);
    }

    // ===== 唤醒认领 CAS =====

    @Test
    void claimWakeShouldReturnTrueWhenSingleRowAffected() throws Exception {
        assertTrue(store.claimWake("sid-1", "t-1"));
        var sql = capturedSql();
        assertTrue(sql.contains("AND status = ?"), "WHERE status = IN_FLIGHT 的 CAS 守卫: " + sql);
        // 认领即收口（CR P1-3）：单语句 IN_FLIGHT→TERMINAL，无中间卡死态
        verify(ps).setString(1, "TERMINAL");
        verify(ps).setString(4, "IN_FLIGHT");
    }

    @Test
    void claimWakeShouldReturnFalseWhenRowAlreadyClaimed() throws Exception {
        when(ps.executeUpdate()).thenReturn(0);
        assertFalse(store.claimWake("sid-1", "t-1"));
    }

    @Test
    void claimWakeFailureShouldReturnFalse() throws Exception {
        when(conn.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("db down"));
        assertFalse(store.claimWake("sid-1", "t-1"));
    }

    // ===== 终态收口 =====

    @Test
    void markGivenUpShouldBindGivenUpStatus() throws Exception {
        store.markTerminal("sid-1", "t-1", true);
        var sql = capturedSql();
        assertTrue(sql.contains("AND status = ?"), "仅 IN_FLIGHT 可收口: " + sql);
        verify(ps).setString(1, "GIVEN_UP");
    }

    // ===== endpoint 快照 =====

    @Test
    void findEndpointShouldReturnValueWhenPresent() throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString("endpoint")).thenReturn("http://booking:8100");
        when(ps.executeQuery()).thenReturn(rs);

        assertEquals(java.util.Optional.of("http://booking:8100"), store.findEndpoint("sid-1", "t-1"));
    }

    @Test
    void findEndpointShouldReturnEmptyWhenAbsent() throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(ps.executeQuery()).thenReturn(rs);

        assertEquals(java.util.Optional.empty(), store.findEndpoint("sid-1", "t-1"));
    }

    @Test
    void findEndpointFailureShouldReturnEmpty() throws Exception {
        when(ps.executeQuery()).thenThrow(new java.sql.SQLException("db down"));
        assertEquals(java.util.Optional.empty(), store.findEndpoint("sid-1", "t-1"));
    }

    // ===== 清理 =====

    @Test
    void deleteTerminalBeforeShouldBindCutoffAndBothTerminalStatuses() throws Exception {
        var cutoff = Timestamp.from(Instant.parse("2026-09-23T00:00:00Z"));
        store.deleteTerminalBefore(cutoff);

        var sql = capturedSql();
        assertTrue(sql.contains("status IN (?, ?)"));
        verify(ps).setString(1, "TERMINAL");
        verify(ps).setString(2, "GIVEN_UP");
        verify(ps).setTimestamp(3, cutoff);
    }

    /** 捕获最近一次预编译的 SQL（mock PreparedStatement 的 toString 不含 SQL，需从 prepareStatement 实参取） */
    private String capturedSql() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        try {
            verify(conn, org.mockito.Mockito.atLeastOnce()).prepareStatement(captor.capture());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return captor.getValue();
    }
}
