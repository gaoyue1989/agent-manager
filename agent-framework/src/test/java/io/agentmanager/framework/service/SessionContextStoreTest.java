package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import io.agentmanager.framework.service.SessionContextStore.UpsertOutcome;

class SessionContextStoreTest {

    private DataSource dataSource;
    private Connection conn;
    private PreparedStatement claim;
    private PreparedStatement lockOwner;
    private PreparedStatement takeOwnership;
    private PreparedStatement lockEntries;
    private PreparedStatement write;
    private ResultSet ownerRows;
    private ResultSet entryRows;

    private SessionContextStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        conn = mock(Connection.class);
        claim = mock(PreparedStatement.class);
        lockOwner = mock(PreparedStatement.class);
        takeOwnership = mock(PreparedStatement.class);
        lockEntries = mock(PreparedStatement.class);
        write = mock(PreparedStatement.class);
        ownerRows = mock(ResultSet.class);
        entryRows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.prepareStatement(argThat(contains("INSERT INTO session_user"))))
            .thenReturn(claim);
        when(conn.prepareStatement(argThat(contains("SELECT user_id FROM session_user"))))
            .thenReturn(lockOwner);
        when(conn.prepareStatement(argThat(contains("UPDATE session_user"))))
            .thenReturn(takeOwnership);
        when(conn.prepareStatement(argThat(contains("content_chars FROM session_context"))))
            .thenReturn(lockEntries);
        when(conn.prepareStatement(argThat(contains("INSERT INTO session_context"))))
            .thenReturn(write);
        when(lockOwner.executeQuery()).thenReturn(ownerRows);
        when(lockEntries.executeQuery()).thenReturn(entryRows);
        store = new SessionContextStore(dataSource, 300);
    }

    private void ownedBy(String owner) throws Exception {
        when(ownerRows.next()).thenReturn(true, false);
        when(ownerRows.getString(1)).thenReturn(owner);
    }

    private static org.mockito.ArgumentMatcher<String> contains(String fragment) {
        return sql -> sql != null && sql.contains(fragment);
    }

    private static org.mockito.ArgumentMatcher<String> endingWith(String suffix) {
        return sql -> sql != null && sql.strip().endsWith(suffix);
    }

    private List<String> executedSql() throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(conn, org.mockito.Mockito.atLeastOnce()).prepareStatement(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void upsertCreatesEntryForNewSessionAndCommits() throws Exception {
        ownedBy("u-1");
        when(entryRows.next()).thenReturn(false);

        var outcome = store.upsert("s-1", "u-1", "headline", "今日头条", "abc");

        assertEquals(new UpsertOutcome.Accepted("headline", "今日头条", 3, 3, 300, true), outcome);
        InOrder order = inOrder(conn, claim, lockOwner, lockEntries, write);
        order.verify(conn).setAutoCommit(false);
        order.verify(claim).executeUpdate();
        order.verify(lockOwner).executeQuery();
        order.verify(lockEntries).executeQuery();
        order.verify(write).executeUpdate();
        order.verify(conn).commit();
        order.verify(conn).setAutoCommit(true);
        verify(takeOwnership, never()).executeUpdate();
        verify(conn, never()).rollback();
    }

    @Test
    void upsertReplacesSameKeyAndKeepsOtherEntries() throws Exception {
        ownedBy("u-1");
        when(entryRows.next()).thenReturn(true, true, false);
        when(entryRows.getString(1)).thenReturn("notice", "headline");
        when(entryRows.getInt(2)).thenReturn(100, 50);

        var outcome = store.upsert("s-1", "u-1", "headline", null, "abcdef");

        assertEquals(new UpsertOutcome.Accepted("headline", "headline", 6, 106, 300, false), outcome);
        verify(conn).commit();
    }

    @Test
    void upsertRejectsForeignSessionWithoutWriting() throws Exception {
        ownedBy("u-2");

        var outcome = store.upsert("s-1", "u-1", "headline", null, "abc");

        assertEquals(new UpsertOutcome.NotOwned(), outcome);
        verify(conn).rollback();
        verify(conn, never()).commit();
        verify(lockEntries, never()).executeQuery();
        verifyNoInteractions(write);
        verify(conn).setAutoCommit(true);
    }

    @Test
    void upsertRejectsReservedSessionIdBeforeTouchingStorage() {
        assertEquals(new UpsertOutcome.NotOwned(), store.upsert("u-1001", "u-1", "headline", null, "abc"));
        assertEquals(new UpsertOutcome.NotOwned(), store.upsert("debug-user", "debug-user", "k", null, "abc"));
        verifyNoInteractions(dataSource);
    }

    @Test
    void upsertClaimsPlaceholderOwner() throws Exception {
        ownedBy(SessionUserStore.PLACEHOLDER_USER);
        when(entryRows.next()).thenReturn(false);

        var outcome = store.upsert("s-1", "u-1", "headline", null, "abc");

        assertEquals(new UpsertOutcome.Accepted("headline", "headline", 3, 3, 300, true), outcome);
        verify(takeOwnership).executeUpdate();
        verify(conn).commit();
    }

    @Test
    void upsertRejectsWhenSessionBudgetExceeded() throws Exception {
        ownedBy("u-1");
        when(entryRows.next()).thenReturn(true, false);
        when(entryRows.getString(1)).thenReturn("notice");
        when(entryRows.getInt(2)).thenReturn(298);

        var outcome = store.upsert("s-1", "u-1", "headline", null, "abc");

        assertEquals(new UpsertOutcome.BudgetExceeded(3, 301, 300), outcome);
        verify(conn).rollback();
        verifyNoInteractions(write);
    }

    @Test
    void upsertValidatesInputBeforeOpeningConnection() {
        assertThrows(IllegalArgumentException.class,
            () -> store.upsert("s-1", "u-1", "bad key!", null, "abc"));
        assertThrows(IllegalArgumentException.class,
            () -> store.upsert("s-1", "u-1", "headline", null, "   "));
        assertThrows(IllegalArgumentException.class,
            () -> store.upsert("s-1", "u-1", "headline", "x".repeat(256), "abc"));
        verifyNoInteractions(dataSource);
    }

    @Test
    void storageFailureRollsBackRestoresAutocommitAndSurfaces() throws Exception {
        ownedBy("u-1");
        when(entryRows.next()).thenThrow(new SQLException("db down"));

        assertThrows(SessionContextStore.StorageException.class,
            () -> store.upsert("s-1", "u-1", "headline", null, "abc"));
        verify(conn).rollback();
        verify(conn, never()).commit();
        verify(conn).setAutoCommit(true);
    }

    @Test
    void ownershipAndBudgetAreDecidedOnLockedRows() throws Exception {
        ownedBy("u-1");
        when(entryRows.next()).thenReturn(false);

        store.upsert("s-1", "u-1", "headline", null, "abc");

        var sqls = executedSql().stream().map(String::strip).toList();
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("SELECT user_id FROM session_user")
            && sql.endsWith("FOR UPDATE")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("content_chars FROM session_context")
            && sql.endsWith("FOR UPDATE")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("ON DUPLICATE KEY UPDATE session_id = session_id")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("INSERT INTO session_context")
            && sql.contains("ON DUPLICATE KEY UPDATE")));
    }

    @Test
    void findBySessionReturnsEntriesInInjectionOrder() throws Exception {
        var find = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(conn.prepareStatement(argThat(contains("ORDER BY id")))).thenReturn(find);
        when(find.executeQuery()).thenReturn(rows);
        var first = Timestamp.valueOf("2026-11-25 09:00:00");
        var later = Timestamp.valueOf("2026-11-25 11:00:00");
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("headline", "notice");
        when(rows.getString(2)).thenReturn("今日头条", "notice");
        when(rows.getString(3)).thenReturn("a", "b");
        when(rows.getInt(4)).thenReturn(1, 1);
        when(rows.getTimestamp(5)).thenReturn(first, first);
        when(rows.getTimestamp(6)).thenReturn(first, later);

        var entries = store.findBySession("s-1");

        assertEquals(List.of(
            new SessionContextStore.Entry("headline", "今日头条", "a", 1, first.toLocalDateTime(), first.toLocalDateTime()),
            new SessionContextStore.Entry("notice", "notice", "b", 1, first.toLocalDateTime(), later.toLocalDateTime())),
            entries);
        var sql = executedSql().get(0);
        assertTrue(sql.contains("WHERE session_id = ? ORDER BY id"));
    }

    @Test
    void findBySessionFailureSurfacesInsteadOfReadingAsNoContext() throws Exception {
        var find = mock(PreparedStatement.class);
        when(conn.prepareStatement(argThat(contains("ORDER BY id")))).thenReturn(find);
        when(find.executeQuery()).thenThrow(new SQLException("db down"));

        assertThrows(SessionContextStore.StorageException.class, () -> store.findBySession("s-1"));
    }

    @Test
    void deleteByKeyOnlyTargetsTheNamedEntry() throws Exception {
        var del = mock(PreparedStatement.class);
        when(conn.prepareStatement(argThat(endingWith("AND context_key = ?")))).thenReturn(del);
        when(del.executeUpdate()).thenReturn(1, 0);

        assertEquals(1, store.deleteByKey("s-1", "headline"));
        assertEquals(0, store.deleteByKey("s-1", "missing"));
    }

    @Test
    void deleteBySessionRemovesEveryEntryOfTheSession() throws Exception {
        var del = mock(PreparedStatement.class);
        when(conn.prepareStatement(argThat(endingWith("WHERE session_id = ?")))).thenReturn(del);
        when(del.executeUpdate()).thenReturn(2);

        assertEquals(2, store.deleteBySession("s-1"));
    }

    @Test
    void deleteOrphansRemovesEntriesWhoseSessionIsGone() throws Exception {
        var del = mock(PreparedStatement.class);
        when(conn.prepareStatement(argThat(contains("LEFT JOIN session_user")))).thenReturn(del);
        when(del.executeUpdate()).thenReturn(3);

        assertEquals(3, store.deleteOrphans());
        assertTrue(executedSql().get(0).contains("WHERE u.session_id IS NULL"));
    }
}
