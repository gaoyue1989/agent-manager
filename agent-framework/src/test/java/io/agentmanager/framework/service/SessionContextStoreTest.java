package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;

import io.agentmanager.framework.service.SessionContextStore.RemoveOutcome;
import io.agentmanager.framework.service.SessionContextStore.UpsertOutcome;

class SessionContextStoreTest {

    private DataSource dataSource;
    private Connection conn;
    private PreparedStatement claim;
    private PreparedStatement lockOwner;
    private PreparedStatement takeOwnership;
    private PreparedStatement lockEntries;
    private PreparedStatement write;
    private PreparedStatement deleteByKey;
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
        deleteByKey = mock(PreparedStatement.class);
        ownerRows = mock(ResultSet.class);
        entryRows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.prepareStatement(argThat(contains("INSERT INTO session_user")))).thenReturn(claim);
        when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("FROM session_user")
            && sql.contains("FOR UPDATE")))).thenReturn(lockOwner);
        when(conn.prepareStatement(argThat(contains("UPDATE session_user")))).thenReturn(takeOwnership);
        when(conn.prepareStatement(argThat(contains("content_chars FROM session_context"))))
            .thenReturn(lockEntries);
        when(conn.prepareStatement(argThat(contains("INSERT INTO session_context")))).thenReturn(write);
        when(conn.prepareStatement(argThat(endingWith("AND context_key = ?")))).thenReturn(deleteByKey);
        when(lockOwner.executeQuery()).thenReturn(ownerRows);
        when(lockEntries.executeQuery()).thenReturn(entryRows);
        store = new SessionContextStore(dataSource, 300);
    }

    private void ownedBy(String owner) throws Exception {
        when(ownerRows.next()).thenReturn(true, false);
        when(ownerRows.getString(1)).thenReturn(owner);
    }

    private static ArgumentMatcher<String> contains(String fragment) {
        return sql -> sql != null && sql.contains(fragment);
    }

    private static ArgumentMatcher<String> endingWith(String suffix) {
        return sql -> sql != null && sql.strip().endsWith(suffix);
    }

    private List<String> executedSql() throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(captor.capture());
        return captor.getAllValues().stream().map(String::strip).toList();
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
    void upsertRejectsForeignSessionWithRollbackAndNoWrite() throws Exception {
        ownedBy("u-2");

        var outcome = store.upsert("s-1", "u-1", "headline", null, "abc");

        assertEquals(new UpsertOutcome.NotOwned(), outcome);
        verify(conn).rollback();
        verify(lockEntries, never()).executeQuery();
        verifyNoInteractions(write);
        verify(conn).setAutoCommit(true);
    }

    @Test
    void upsertRejectsReservedSessionIdsIncludingCaseVariantsBeforeTouchingStorage() {
        assertEquals(new UpsertOutcome.NotOwned(), store.upsert("u-1001", "u-1", "headline", null, "abc"));
        assertEquals(new UpsertOutcome.NotOwned(), store.upsert("U-777777", "u-1", "headline", null, "abc"));
        assertEquals(new UpsertOutcome.NotOwned(), store.upsert("DEBUG-USER", "u-1", "headline", null, "abc"));
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
        assertThrows(IllegalArgumentException.class,
            () -> store.upsert("s-1", "u-1", "headline", "标题\n\n## Subagents", "abc"));
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
    void autocommitRestoreFailureIsSuppressedWhenTheTransactionAlreadyFailed() throws Exception {
        ownedBy("u-1");
        when(entryRows.next()).thenThrow(new SQLException("db down"));
        org.mockito.Mockito.doThrow(new SQLException("pool closed")).when(conn).setAutoCommit(true);

        var thrown = assertThrows(SessionContextStore.StorageException.class,
            () -> store.upsert("s-1", "u-1", "headline", null, "abc"));

        assertEquals(1, thrown.getCause().getSuppressed().length);
        verify(conn).rollback();
    }

    @Test
    void autocommitRestoreFailureAfterASuccessfulCommitSurfaces() throws Exception {
        ownedBy("u-1");
        when(entryRows.next()).thenReturn(false);
        org.mockito.Mockito.doThrow(new SQLException("pool closed")).when(conn).setAutoCommit(true);

        assertThrows(SessionContextStore.StorageException.class,
            () -> store.upsert("s-1", "u-1", "headline", null, "abc"));
        verify(conn).commit();
        verify(conn, never()).rollback();
    }

    @Test
    void claimingRefreshesTheRetentionClockWithoutTouchingOwnership() throws Exception {
        ownedBy("u-1");
        when(entryRows.next()).thenReturn(false);

        store.upsert("s-1", "u-1", "headline", null, "abc");

        var claimSql = executedSql().stream().filter(sql -> sql.contains("INSERT INTO session_user"))
            .findFirst().orElseThrow();
        assertTrue(claimSql.contains("ON DUPLICATE KEY UPDATE updated_at = NOW(3)"));
        assertTrue(!claimSql.contains("user_id = VALUES"));
        assertTrue(!claimSql.contains("ON UPDATE CURRENT_TIMESTAMP"));
    }

    @Test
    void ownershipAndBudgetAreDecidedOnLockedRows() throws Exception {
        ownedBy("u-1");
        when(entryRows.next()).thenReturn(false);

        store.upsert("s-1", "u-1", "headline", null, "abc");

        var sqls = executedSql();
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("SELECT user_id FROM session_user")
            && sql.endsWith("FOR UPDATE")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("content_chars FROM session_context")
            && sql.endsWith("FOR UPDATE")));
        assertTrue(sqls.stream().anyMatch(sql -> sql.contains("INSERT INTO session_context")
            && sql.contains("ON DUPLICATE KEY UPDATE")));
    }

    @Test
    void findBySessionReturnsEntriesInInjectionOrder() throws Exception {
        var find = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("ORDER BY id"))))
            .thenReturn(find);
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
        assertTrue(executedSql().get(0).contains("WHERE session_id = ? ORDER BY id"));
    }

    @Test
    void findBySessionFailureSurfacesInsteadOfReadingAsNoContext() throws Exception {
        var find = mock(PreparedStatement.class);
        when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("ORDER BY id"))))
            .thenReturn(find);
        when(find.executeQuery()).thenThrow(new SQLException("db down"));

        assertThrows(SessionContextStore.StorageException.class, () -> store.findBySession("s-1"));
    }

    @Test
    void removeDeletesTheNamedEntryForItsOwner() throws Exception {
        ownedBy("u-1");
        when(deleteByKey.executeUpdate()).thenReturn(1);

        var outcome = store.remove("s-1", "u-1", "headline");

        assertEquals(new RemoveOutcome.Deleted("headline"), outcome);
        InOrder order = inOrder(lockOwner, deleteByKey, conn);
        order.verify(conn).setAutoCommit(false);
        order.verify(lockOwner).executeQuery();
        order.verify(deleteByKey).executeUpdate();
        order.verify(conn).commit();
        order.verify(conn).setAutoCommit(true);
        verify(deleteByKey).setString(1, "s-1");
        verify(deleteByKey).setString(2, "headline");
    }

    @Test
    void removeReportsAnUnknownKey() throws Exception {
        ownedBy("u-1");
        when(deleteByKey.executeUpdate()).thenReturn(0);

        assertEquals(new RemoveOutcome.NotFound("missing"), store.remove("s-1", "u-1", "missing"));
    }

    @Test
    void removeRejectsAForeignUserWithRollbackAndNoDelete() throws Exception {
        ownedBy("u-2");

        var outcome = store.remove("s-1", "u-1", "headline");

        assertEquals(new RemoveOutcome.NotOwned(), outcome);
        verify(conn).rollback();
        verifyNoInteractions(deleteByKey);
        verify(conn).setAutoCommit(true);
    }

    @Test
    void removeRejectsReservedSessionIdsBeforeTouchingStorage() {
        assertEquals(new RemoveOutcome.NotOwned(), store.remove("U-777777", "u-1", "k"));
        assertEquals(new RemoveOutcome.NotOwned(), store.remove("debug-user", "debug-user", "k"));
        assertThrows(IllegalArgumentException.class, () -> store.remove("s-1", "u-1", " "));
        verifyNoInteractions(dataSource);
    }

    @Test
    void ownerOfAnswersWithoutLockingAndSurfacesStorageFailure() throws Exception {
        var find = mock(PreparedStatement.class);
        var present = mock(ResultSet.class);
        var absent = mock(ResultSet.class);
        when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains("SELECT user_id FROM session_user")
            && !sql.contains("FOR UPDATE")))).thenReturn(find);
        when(present.next()).thenReturn(true, false);
        when(present.getString(1)).thenReturn("u-7");
        when(absent.next()).thenReturn(false);
        when(find.executeQuery()).thenReturn(present, absent).thenThrow(new SQLException("db down"));

        assertEquals("u-7", store.ownerOf("s-1"));
        assertNull(store.ownerOf("s-2"));
        assertThrows(SessionContextStore.StorageException.class, () -> store.ownerOf("s-3"));
        verify(conn, never()).setAutoCommit(false);
    }

    @Test
    void deleteBySessionRemovesEveryEntryOfTheSession() throws Exception {
        var del = mock(PreparedStatement.class);
        when(conn.prepareStatement(argThat(sql -> sql != null
            && sql.strip().equals("DELETE FROM session_context WHERE session_id = ?")))).thenReturn(del);
        when(del.executeUpdate()).thenReturn(2);

        assertEquals(2, store.deleteBySession("s-1"));
    }

    @Test
    void deleteOrphansSelectsIdsWithoutLockingThenDeletesByPrimaryKey() throws Exception {
        var select = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        var del = mock(PreparedStatement.class);
        when(conn.prepareStatement(argThat(contains("WHERE u.session_id IS NULL")))).thenReturn(select);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getLong(1)).thenReturn(5L, 6L, 7L);
        when(conn.prepareStatement(argThat(contains("DELETE FROM session_context WHERE id IN (?,?,?)"))))
            .thenReturn(del);
        when(del.executeUpdate()).thenReturn(3);

        assertEquals(3, store.deleteOrphans());

        var selectSql = executedSql().get(0);
        assertTrue(!selectSql.contains("FOR UPDATE"));
        assertTrue(selectSql.contains("LIMIT ?"));
        verify(select).setInt(1, 1000);
        verify(del).setLong(1, 5L);
        verify(del).setLong(3, 7L);
        verify(select, times(1)).executeQuery();
        verify(conn, never()).setAutoCommit(false);
    }

    private static ResultSet fullBatch() throws Exception {
        var rows = mock(ResultSet.class);
        var flags = new Boolean[SessionContextStore.ORPHAN_BATCH_SIZE + 1];
        java.util.Arrays.fill(flags, true);
        flags[SessionContextStore.ORPHAN_BATCH_SIZE] = false;
        when(rows.next()).thenReturn(flags[0], java.util.Arrays.copyOfRange(flags, 1, flags.length));
        when(rows.getLong(1)).thenReturn(1L);
        return rows;
    }

    @Test
    void deleteOrphansKeepsBatchingUntilAShortBatchAndStops() throws Exception {
        var select = mock(PreparedStatement.class);
        var del = mock(PreparedStatement.class);
        var empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        var first = fullBatch();
        when(conn.prepareStatement(argThat(contains("WHERE u.session_id IS NULL")))).thenReturn(select);
        when(conn.prepareStatement(argThat(contains("DELETE FROM session_context WHERE id IN")))).thenReturn(del);
        when(select.executeQuery()).thenReturn(first, empty);
        when(del.executeUpdate()).thenReturn(1000);

        assertEquals(1000, store.deleteOrphans());

        verify(select, times(2)).executeQuery();
        verify(del, times(1)).executeUpdate();
        verify(del).setLong(SessionContextStore.ORPHAN_BATCH_SIZE, 1L);
    }

    @Test
    void deleteOrphansIsBoundedByTheBatchBudget() throws Exception {
        var select = mock(PreparedStatement.class);
        var del = mock(PreparedStatement.class);
        when(conn.prepareStatement(argThat(contains("WHERE u.session_id IS NULL")))).thenReturn(select);
        when(conn.prepareStatement(argThat(contains("DELETE FROM session_context WHERE id IN")))).thenReturn(del);
        var batches = new ResultSet[SessionContextStore.ORPHAN_MAX_BATCHES];
        for (int i = 0; i < batches.length; i++) {
            batches[i] = fullBatch();
        }
        when(select.executeQuery()).thenReturn(batches[0], java.util.Arrays.copyOfRange(batches, 1, batches.length));
        when(del.executeUpdate()).thenReturn(1000);

        assertEquals(SessionContextStore.ORPHAN_BATCH_SIZE * SessionContextStore.ORPHAN_MAX_BATCHES,
            store.deleteOrphans());

        verify(select, times(SessionContextStore.ORPHAN_MAX_BATCHES)).executeQuery();
    }

    @Test
    void deleteOrphansPreparesNoDeleteWhenThereIsNothingToRemove() throws Exception {
        var select = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(conn.prepareStatement(argThat(contains("WHERE u.session_id IS NULL")))).thenReturn(select);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);

        assertEquals(0, store.deleteOrphans());

        assertEquals(1, executedSql().size());
    }
}
