package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UI 交互上下文存储测试（4.7：独立表 + 覆盖式 upsert + 注入文本渲染）。
 */
class UiContextStoreTest {

    private DataSource dataSource;
    private UiContextStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        var conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn);
        // initSchema: CREATE TABLE IF NOT EXISTS
        var stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        store = new UiContextStore(dataSource);
    }

    @Test
    void upsertShouldWriteContentAndStructuredContext() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        store.upsert("tenant:s1", "clock: 12:00", java.util.Map.of("time", "12:00"));

        verify(ps).setString(1, "tenant:s1");
        verify(ps).setString(2, "clock: 12:00");
        verify(ps).setString(3, "{\"time\":\"12:00\"}");
        verify(ps).executeUpdate();
    }

    @Test
    void upsertShouldAllowNullContentWithStructuredOnly() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        store.upsert("tenant:s2", null, java.util.List.of(1, 2));

        verify(ps).setString(2, null);
        verify(ps).setString(3, "[1,2]");
    }

    @Test
    void findBySessionShouldReturnStoredContext() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        var rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("content")).thenReturn("clock: 13:00");
        when(rs.getString("structured_context")).thenReturn("{\"time\":\"13:00\"}");
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.valueOf("2026-08-19 10:00:00"));

        var result = store.findBySession("tenant:s3");

        assertTrue(result.isPresent());
        assertEquals("clock: 13:00", result.get().content());
        assertEquals("{\"time\":\"13:00\"}", result.get().structuredContext());
    }

    @Test
    void findBySessionShouldReturnEmptyWhenAbsent() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(mock(ResultSet.class));

        assertEquals(Optional.empty(), store.findBySession("tenant:s4"));
    }

    @Test
    void findBySessionShouldReturnEmptyOnError() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("db down"));

        assertEquals(Optional.empty(), store.findBySession("tenant:s5"));
    }

    @Test
    void validateSessionIdShouldRejectInvalidFormats() {
        assertThrows(IllegalArgumentException.class, () -> UiContextStore.validateSessionId(null));
        assertThrows(IllegalArgumentException.class, () -> UiContextStore.validateSessionId(""));
        assertThrows(IllegalArgumentException.class, () -> UiContextStore.validateSessionId("no-separator"));
        assertThrows(IllegalArgumentException.class, () -> UiContextStore.validateSessionId(":thread"));
        assertThrows(IllegalArgumentException.class, () -> UiContextStore.validateSessionId("tenant:"));
    }

    @Test
    void validateSessionIdShouldAcceptTenantThread() {
        assertEquals("debug-user:abc", UiContextStore.validateSessionId("debug-user:abc"));
        assertEquals("a:b:c", UiContextStore.validateSessionId("a:b:c"));
    }

    @Test
    void renderInjectTextShouldCombineContentAndStructured() {
        var ctx = new UiContextStore.UiContext("clock: 12:00", "{\"time\":\"12:00\"}", null);
        var text = UiContextStore.renderInjectText(ctx);
        assertTrue(text.contains("clock: 12:00"));
        assertTrue(text.contains("{\"time\":\"12:00\"}"));
    }

    @Test
    void renderInjectTextShouldHandleNulls() {
        assertNull(UiContextStore.renderInjectText(null));
        assertNull(UiContextStore.renderInjectText(new UiContextStore.UiContext(null, null, null)));
        var text = UiContextStore.renderInjectText(new UiContextStore.UiContext(null, "[1]", null));
        assertTrue(text.contains("[1]"));
        var text2 = UiContextStore.renderInjectText(new UiContextStore.UiContext("only content", null, null));
        assertTrue(text2.contains("only content"));
        assertFalse(text2.contains("结构化"));
    }
}