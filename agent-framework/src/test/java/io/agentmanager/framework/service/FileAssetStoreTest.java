package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * file_asset 表元数据存储单测（UT-05/06）：DDL 幂等、CRUD、pending→injected 状态机。
 * 与 UiContextStoreTest 同款 mock DataSource 模式（不连真实 DB）。
 */
class FileAssetStoreTest {

    private DataSource dataSource;
    private FileAssetStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        var conn = mock(Connection.class);
        var stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        store = new FileAssetStore(dataSource);
    }

    private FileAssetStore.FileAsset asset(String id, String userKey, String status) {
        return new FileAssetStore.FileAsset(id, userKey, "s1", "a.csv",
            null, "text/csv", 10, "local", "upload/k/" + id + ".csv", "upload", status,
            LocalDateTime.now());
    }

    @Test
    void ddlShouldRunAtConstruction() throws Exception {
        verify(dataSource.getConnection()).createStatement();
        // 幂等：再次构造（新 mock）不抛错
        var ds2 = mock(DataSource.class);
        when(ds2.getConnection()).thenReturn(mock(Connection.class));
        var conn2 = mock(Connection.class);
        when(ds2.getConnection()).thenReturn(conn2);
        when(conn2.createStatement()).thenReturn(mock(Statement.class));
        new FileAssetStore(ds2);
    }

    @Test
    void insertShouldPersistAllColumns() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        store.insert(asset("id-1", "alice", "pending"));

        verify(ps).setString(1, "id-1");
        verify(ps).setString(2, "alice");
        verify(ps).setString(4, "a.csv");
        verify(ps).setLong(7, 10);
        verify(ps).setString(8, "local");
        verify(ps).setString(9, "upload/k/id-1.csv");
        verify(ps).setString(10, "upload");
        verify(ps).setString(11, "pending");
        verify(ps).executeUpdate();
    }

    @Test
    void getShouldMapRow() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        var rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true).thenReturn(false);
        when(rs.getString("id")).thenReturn("id-1");
        when(rs.getString("user_key")).thenReturn("alice");
        when(rs.getString("session_id")).thenReturn("s1");
        when(rs.getString("file_name")).thenReturn("a.csv");
        when(rs.getString("workspace_path")).thenReturn("uploads/a_1.csv");
        when(rs.getString("mime_type")).thenReturn("text/csv");
        when(rs.getLong("size")).thenReturn(10L);
        when(rs.getString("storage_type")).thenReturn("local");
        when(rs.getString("storage_key")).thenReturn("upload/k/id-1.csv");
        when(rs.getString("origin")).thenReturn("upload");
        when(rs.getString("status")).thenReturn("pending");
        when(rs.getTimestamp("created_at")).thenReturn(java.sql.Timestamp.valueOf(LocalDateTime.now()));

        var got = store.get("id-1");
        assertTrue(got.isPresent());
        assertEquals("uploads/a_1.csv", got.get().workspacePath());
        assertEquals("text/csv", got.get().mimeType());
    }

    @Test
    void listPendingShouldFilterByUserAndStatus() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        var rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true).thenReturn(true).thenReturn(false);
        when(rs.getString("id")).thenReturn("a", "b");
        when(rs.getString("user_key")).thenReturn("alice");
        when(rs.getString("session_id")).thenReturn("s1");
        when(rs.getString("file_name")).thenReturn("a.csv");
        when(rs.getString("workspace_path")).thenReturn(null);
        when(rs.getString("mime_type")).thenReturn("text/csv");
        when(rs.getLong("size")).thenReturn(10L);
        when(rs.getString("storage_type")).thenReturn("local");
        when(rs.getString("storage_key")).thenReturn("k");
        when(rs.getString("origin")).thenReturn("upload");
        when(rs.getString("status")).thenReturn("pending");
        when(rs.getTimestamp("created_at")).thenReturn(java.sql.Timestamp.valueOf(LocalDateTime.now()));

        assertEquals(2, store.listPending("alice", null).size());
        verify(ps).setString(1, "alice");
    }

    @Test
    void markInjectedShouldUpdatePendingOnly() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        store.markInjected(List.of("id-1", "id-2"));
        verify(ps).setString(1, "id-1");
        verify(ps).setString(1, "id-2");
        verify(ps, org.mockito.Mockito.times(2)).addBatch();
        verify(ps).executeBatch();
    }

    @Test
    void resetInjectedToPendingShouldUpdateByUser() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(2);

        store.resetInjectedToPending("alice");
        verify(ps).setString(1, "alice");
        verify(ps).executeUpdate();
    }

    @Test
    void updateWorkspacePathShouldPersist() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        store.updateWorkspacePath("id-1", "uploads/a_1.csv");
        verify(ps).setString(1, "uploads/a_1.csv");
        verify(ps).setString(2, "id-1");
        verify(ps).executeUpdate();
    }

    @Test
    void getMissingShouldReturnEmpty() throws Exception {
        var conn = dataSource.getConnection();
        var ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        var rs = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertTrue(store.get("missing").isEmpty());
    }
}