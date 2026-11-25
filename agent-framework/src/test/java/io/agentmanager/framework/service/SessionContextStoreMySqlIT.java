package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.mysql.cj.jdbc.MysqlDataSource;

import io.agentmanager.framework.service.SessionContextStore.RemoveOutcome;
import io.agentmanager.framework.service.SessionContextStore.UpsertOutcome;

@EnabledIfEnvironmentVariable(named = "HITL_MYSQL_IT", matches = "1")
class SessionContextStoreMySqlIT {

    private MysqlDataSource dataSource;
    private SessionContextStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new MysqlDataSource();
        dataSource.setURL(System.getenv("CHECKPOINT_JDBC_URL"));
        dataSource.setUser(System.getenv("CHECKPOINT_USERNAME"));
        dataSource.setPassword(System.getenv("CHECKPOINT_PASSWORD"));
        io.agentmanager.framework.support.TestSchemaMigrator.migrate(dataSource);
        store = new SessionContextStore(dataSource, 10_000);
    }

    private static String sid(String tag) {
        return "it-ctx_" + tag + "_" + UUID.randomUUID();
    }

    private void cleanup(String... sessionIds) throws Exception {
        try (var conn = dataSource.getConnection()) {
            for (var table : List.of("session_context", "session_user")) {
                try (var del = conn.prepareStatement("DELETE FROM " + table + " WHERE session_id = ?")) {
                    for (var sessionId : sessionIds) {
                        del.setString(1, sessionId);
                        del.addBatch();
                    }
                    del.executeBatch();
                }
            }
        }
    }

    private void cleanupByPrefix(String prefix) throws Exception {
        try (var conn = dataSource.getConnection()) {
            for (var table : List.of("session_context", "session_user")) {
                try (var del = conn.prepareStatement("DELETE FROM " + table + " WHERE session_id LIKE ?")) {
                    del.setString(1, prefix + "%");
                    del.executeUpdate();
                }
            }
        }
    }

    private String ownerOf(String sessionId) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT user_id FROM session_user WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Timestamp updatedAtOf(String sessionId) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT updated_at FROM session_user WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1) : null;
            }
        }
    }

    @Test
    void upsertClaimsTheSessionAndKeepsTheLatestContentPerKey() throws Exception {
        var sessionId = sid("lifecycle");
        try {
            assertEquals(new UpsertOutcome.Accepted("headline", "头条", 4, 4, 10_000, true),
                store.upsert(sessionId, "u-1", "headline", "头条", "aaaa"));
            assertEquals("u-1", ownerOf(sessionId));
            assertEquals("u-1", store.ownerOf(sessionId));

            assertEquals(new UpsertOutcome.Accepted("headline", "头条", 2, 2, 10_000, false),
                store.upsert(sessionId, "u-1", "headline", "头条", "bb"));
            assertEquals(new UpsertOutcome.Accepted("notice", "notice", 3, 5, 10_000, true),
                store.upsert(sessionId, "u-1", "notice", null, "ccc"));

            var entries = store.findBySession(sessionId);
            assertEquals(List.of("headline", "notice"), entries.stream().map(SessionContextStore.Entry::key).toList());
            assertEquals("bb", entries.get(0).content());
            assertEquals(2, entries.get(0).contentChars());
            assertTrue(entries.get(0).createdAt() != null && entries.get(0).updatedAt() != null);

            assertTrue(((UpsertOutcome.Accepted) store.upsert(
                sessionId, "u-1", "Headline", null, "d")).created());
            assertEquals(3, store.findBySession(sessionId).size());
        } finally {
            cleanup(sessionId);
        }
    }

    @Test
    void injectingKeepsTheSessionAliveWithoutChangingItsOwner() throws Exception {
        var sessionId = sid("retention");
        try {
            store.upsert(sessionId, "u-1", "k", null, "a");
            var firstActivity = updatedAtOf(sessionId);
            Thread.sleep(1100);

            store.upsert(sessionId, "u-1", "k", null, "b");

            assertTrue(updatedAtOf(sessionId).after(firstActivity));
            assertEquals("u-1", ownerOf(sessionId));
        } finally {
            cleanup(sessionId);
        }
    }

    @Test
    void upsertRejectsForeignUserAndReservedSessionIds() throws Exception {
        var sessionId = sid("ownership");
        var reserved = List.of("u-" + UUID.randomUUID(), "U-" + UUID.randomUUID(), "debug-user",
            "DEBUG-USER", "Debug-User", "gw-" + UUID.randomUUID(), "GW-" + UUID.randomUUID());
        try {
            store.upsert(sessionId, "u-1", "headline", null, "aaaa");

            assertEquals(new UpsertOutcome.NotOwned(), store.upsert(sessionId, "u-2", "headline", null, "hack"));
            assertEquals("u-1", ownerOf(sessionId));
            assertEquals("aaaa", store.findBySession(sessionId).get(0).content());

            for (var value : reserved) {
                assertEquals(new UpsertOutcome.NotOwned(), store.upsert(value, "u-9", "k", null, "a"), value);
                assertNull(store.ownerOf(value), value);
            }
        } finally {
            cleanup(sessionId);
            cleanup(reserved.toArray(String[]::new));
        }
    }

    @Test
    void upsertClaimsPlaceholderOwnership() throws Exception {
        var sessionId = sid("placeholder");
        try {
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                     "INSERT INTO session_user (session_id, user_id, created_at, updated_at) VALUES (?, ?, NOW(3), NOW(3))")) {
                ps.setString(1, sessionId);
                ps.setString(2, SessionUserStore.PLACEHOLDER_USER);
                ps.executeUpdate();
            }

            store.upsert(sessionId, "u-9", "k", null, "a");

            assertEquals("u-9", ownerOf(sessionId));
        } finally {
            cleanup(sessionId);
        }
    }

    @Test
    void upsertEnforcesTheSessionBudget() throws Exception {
        var sessionId = sid("budget");
        var limited = new SessionContextStore(dataSource, 10);
        try {
            assertEquals(new UpsertOutcome.Accepted("a", "a", 6, 6, 10, true),
                limited.upsert(sessionId, "u-1", "a", null, "aaaaaa"));
            assertEquals(new UpsertOutcome.BudgetExceeded(5, 11, 10),
                limited.upsert(sessionId, "u-1", "b", null, "bbbbb"));
            assertEquals(new UpsertOutcome.Accepted("a", "a", 1, 1, 10, false),
                limited.upsert(sessionId, "u-1", "a", null, "a"));
            assertEquals(new UpsertOutcome.Accepted("b", "b", 5, 6, 10, true),
                limited.upsert(sessionId, "u-1", "b", null, "bbbbb"));
            assertEquals(List.of("a", "b"), store.findBySession(sessionId).stream()
                .map(SessionContextStore.Entry::key).toList());
        } finally {
            cleanup(sessionId);
        }
    }

    @Test
    void concurrentUpsertsNeverExceedTheBudget() throws Exception {
        var sessionId = sid("concurrent");
        var limited = new SessionContextStore(dataSource, 1_000);
        var workers = 24;
        var latch = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(workers);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<UpsertOutcome>>();
            for (var i = 0; i < workers; i++) {
                var key = "k" + i;
                futures.add(pool.submit(() -> {
                    latch.await();
                    return limited.upsert(sessionId, "u-1", key, null, "x".repeat(100));
                }));
            }
            latch.countDown();
            var accepted = 0;
            for (var future : futures) {
                var outcome = future.get(60, TimeUnit.SECONDS);
                assertTrue(outcome instanceof UpsertOutcome.Accepted
                    || outcome instanceof UpsertOutcome.BudgetExceeded);
                if (outcome instanceof UpsertOutcome.Accepted) {
                    accepted++;
                }
            }
            assertTrue(accepted <= 10);
            assertEquals(accepted, store.findBySession(sessionId).size());
            assertEquals(accepted * 100, store.findBySession(sessionId).stream()
                .mapToInt(SessionContextStore.Entry::contentChars).sum());
        } finally {
            pool.shutdownNow();
            cleanup(sessionId);
        }
    }

    @Test
    void removeOnlyTouchesEntriesOfTheRequestersOwnSession() throws Exception {
        var sessionId = sid("remove");
        try {
            store.upsert(sessionId, "u-1", "headline", null, "a");
            store.upsert(sessionId, "u-1", "notice", null, "b");

            assertEquals(new RemoveOutcome.NotOwned(), store.remove(sessionId, "u-2", "headline"));
            assertEquals(2, store.findBySession(sessionId).size());
            assertEquals(new RemoveOutcome.NotFound("missing"), store.remove(sessionId, "u-1", "missing"));
            assertEquals(new RemoveOutcome.Deleted("headline"), store.remove(sessionId, "u-1", "headline"));
            assertEquals(List.of("notice"), store.findBySession(sessionId).stream()
                .map(SessionContextStore.Entry::key).toList());

            assertEquals(1, store.deleteBySession(sessionId));
            assertTrue(store.findBySession(sessionId).isEmpty());
        } finally {
            cleanup(sessionId);
        }
    }

    @Test
    void deleteOrphansKeepsEntriesOfLiveSessions() throws Exception {
        var live = sid("orphan-live");
        var gone = sid("orphan-gone");
        try {
            store.upsert(live, "u-1", "k", null, "a");
            store.upsert(gone, "u-1", "k", null, "b");
            try (var conn = dataSource.getConnection();
                 var del = conn.prepareStatement("DELETE FROM session_user WHERE session_id = ?")) {
                del.setString(1, gone);
                del.executeUpdate();
            }

            assertEquals(1, store.deleteOrphans());
            assertEquals(1, store.findBySession(live).size());
            assertTrue(store.findBySession(gone).isEmpty());
            assertEquals(0, store.deleteOrphans());
        } finally {
            cleanup(live, gone);
        }
    }

    @Test
    void deleteOrphansSweepsMoreThanOneBatchAndDoesNotDeadlockWithConcurrentInjects() throws Exception {
        var prefix = "it-ctx_sweep_" + UUID.randomUUID() + "_";
        var live = sid("sweep-live");
        var total = SessionContextStore.ORPHAN_BATCH_SIZE + 205;
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            try (var conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement(
                    "INSERT INTO session_context (session_id, context_key, content, content_chars, user_id) VALUES (?, 'k', 'x', 1, 'u-gone')")) {
                    for (int i = 0; i < total; i++) {
                        ps.setString(1, prefix + i);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            }
            var injector = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 12; i++) {
                    store.upsert(live, "u-1", "k" + (i % 3), null, "live" + i);
                }
                return true;
            });
            var sweeper = pool.submit(() -> {
                start.await();
                int removed = 0;
                for (int i = 0; i < 4; i++) {
                    removed += store.deleteOrphans();
                }
                return removed;
            });
            start.countDown();
            assertTrue(injector.get(60, TimeUnit.SECONDS));
            assertTrue(sweeper.get(60, TimeUnit.SECONDS) >= total);
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement("SELECT COUNT(*) FROM session_context WHERE session_id LIKE ?")) {
                ps.setString(1, prefix + "%");
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
            }
            assertEquals(3, store.findBySession(live).size());
        } finally {
            pool.shutdownNow();
            cleanupByPrefix(prefix);
            cleanup(live);
        }
    }

    @Test
    void chineseContentRoundTrips() throws Exception {
        var sessionId = sid("unicode");
        var content = "1. 今日头条：平台发布新版本\n2. 公告：系统维护（含 emoji 🚀）";
        try {
            store.upsert(sessionId, "u-1", "headline", "今日头条", content);
            assertEquals(content, store.findBySession(sessionId).get(0).content());
            assertEquals(content.length(), store.findBySession(sessionId).get(0).contentChars());
        } finally {
            cleanup(sessionId);
        }
    }

    @Test
    void closedStorageSurfacesAsStorageFailure() {
        var broken = new MysqlDataSource();
        broken.setURL("jdbc:mysql://127.0.0.1:1/none?connectTimeout=500&socketTimeout=500");
        broken.setUser("none");
        broken.setPassword("none");
        var offline = new SessionContextStore(broken, 10);
        assertThrows(SessionContextStore.StorageException.class, () -> offline.findBySession("s"));
        assertThrows(SessionContextStore.StorageException.class, () -> offline.ownerOf("s"));
    }
}
