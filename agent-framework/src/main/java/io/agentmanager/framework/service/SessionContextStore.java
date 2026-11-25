package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

public class SessionContextStore {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_TITLE_CHARS = 255;
    private static final String STORAGE_ERROR = "session context store unavailable";
    static final int ORPHAN_BATCH_SIZE = 1000;
    static final int ORPHAN_MAX_BATCHES = 20;

    private static final String CLAIM_SESSION_SQL = """
        INSERT INTO session_user (session_id, user_id, created_at, updated_at)
        VALUES (?, ?, NOW(3), NOW(3))
        ON DUPLICATE KEY UPDATE updated_at = NOW(3)
        """;

    private static final String LOCK_OWNER_SQL = """
        SELECT user_id FROM session_user WHERE session_id = ? FOR UPDATE
        """;

    private static final String FIND_OWNER_SQL = """
        SELECT user_id FROM session_user WHERE session_id = ?
        """;

    private static final String TAKE_OWNERSHIP_SQL = """
        UPDATE session_user SET user_id = ? WHERE session_id = ?
        """;

    private static final String LOCK_ENTRIES_SQL = """
        SELECT context_key, content_chars FROM session_context WHERE session_id = ? FOR UPDATE
        """;

    private static final String WRITE_SQL = """
        INSERT INTO session_context (session_id, context_key, title, content, content_chars, user_id)
        VALUES (?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE title = VALUES(title), content = VALUES(content),
            content_chars = VALUES(content_chars), user_id = VALUES(user_id)
        """;

    private static final String FIND_SQL = """
        SELECT context_key, title, content, content_chars, created_at, updated_at
        FROM session_context WHERE session_id = ? ORDER BY id
        """;

    private static final String DELETE_BY_KEY_SQL = """
        DELETE FROM session_context WHERE session_id = ? AND context_key = ?
        """;

    private static final String DELETE_BY_SESSION_SQL = """
        DELETE FROM session_context WHERE session_id = ?
        """;

    private static final String FIND_ORPHAN_IDS_SQL = """
        SELECT c.id FROM session_context c
        LEFT JOIN session_user u ON u.session_id = c.session_id
        WHERE u.session_id IS NULL
        ORDER BY c.id
        LIMIT ?
        """;

    private final DataSource dataSource;
    private final int maxTotalChars;

    public SessionContextStore(DataSource dataSource, int maxTotalChars) {
        if (maxTotalChars <= 0) {
            throw new IllegalArgumentException("maxTotalChars must be positive");
        }
        this.dataSource = dataSource;
        this.maxTotalChars = maxTotalChars;
    }

    public record Entry(String key, String title, String content, int contentChars,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public sealed interface UpsertOutcome {

        record Accepted(String key, String title, int contentChars, int totalChars, int maxTotalChars,
                        boolean created) implements UpsertOutcome {
        }

        record NotOwned() implements UpsertOutcome {
        }

        record BudgetExceeded(int contentChars, int totalChars, int maxTotalChars) implements UpsertOutcome {
        }
    }

    public sealed interface RemoveOutcome {

        record Deleted(String key) implements RemoveOutcome {
        }

        record NotFound(String key) implements RemoveOutcome {
        }

        record NotOwned() implements RemoveOutcome {
        }
    }

    public static class StorageException extends RuntimeException {

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface TransactionWork<T> {

        T run(Connection conn) throws SQLException;
    }

    private record LockedEntries(int otherChars, boolean keyExists) {
    }

    public int maxTotalChars() {
        return maxTotalChars;
    }

    public UpsertOutcome upsert(String sessionId, String requester, String key, String title, String content) {
        validate(sessionId, requester, key, title, content);
        if (SessionKeyNamespace.isReservedSessionId(sessionId)) {
            return new UpsertOutcome.NotOwned();
        }
        var effectiveTitle = title == null || title.isBlank() ? key : title;
        return inTransaction(conn -> {
            try (var claim = conn.prepareStatement(CLAIM_SESSION_SQL)) {
                claim.setString(1, sessionId);
                claim.setString(2, requester);
                claim.executeUpdate();
            }
            var owner = lockOwner(conn, sessionId);
            if (!SessionAccessGuard.isOwner(owner, requester)) {
                conn.rollback();
                return new UpsertOutcome.NotOwned();
            }
            if (SessionUserStore.PLACEHOLDER_USER.equals(owner)) {
                try (var take = conn.prepareStatement(TAKE_OWNERSHIP_SQL)) {
                    take.setString(1, requester);
                    take.setString(2, sessionId);
                    take.executeUpdate();
                }
            }
            var locked = lockEntries(conn, sessionId, key);
            var totalChars = locked.otherChars() + content.length();
            if (totalChars > maxTotalChars) {
                conn.rollback();
                return new UpsertOutcome.BudgetExceeded(content.length(), totalChars, maxTotalChars);
            }
            try (var write = conn.prepareStatement(WRITE_SQL)) {
                write.setString(1, sessionId);
                write.setString(2, key);
                write.setString(3, effectiveTitle);
                write.setString(4, content);
                write.setInt(5, content.length());
                write.setString(6, requester);
                write.executeUpdate();
            }
            return new UpsertOutcome.Accepted(key, effectiveTitle, content.length(), totalChars, maxTotalChars,
                !locked.keyExists());
        });
    }

    public RemoveOutcome remove(String sessionId, String requester, String key) {
        if (sessionId == null || sessionId.isBlank() || requester == null || requester.isBlank()
                || key == null || key.isBlank()) {
            throw new IllegalArgumentException("sessionId, requester and key are required");
        }
        if (SessionKeyNamespace.isReservedSessionId(sessionId)) {
            return new RemoveOutcome.NotOwned();
        }
        return inTransaction(conn -> {
            var owner = lockOwner(conn, sessionId);
            if (!SessionAccessGuard.isOwner(owner, requester)) {
                conn.rollback();
                return new RemoveOutcome.NotOwned();
            }
            int rows;
            try (var del = conn.prepareStatement(DELETE_BY_KEY_SQL)) {
                del.setString(1, sessionId);
                del.setString(2, key);
                rows = del.executeUpdate();
            }
            return rows == 0 ? new RemoveOutcome.NotFound(key) : new RemoveOutcome.Deleted(key);
        });
    }

    public String ownerOf(String sessionId) {
        try (var conn = dataSource.getConnection();
             var query = conn.prepareStatement(FIND_OWNER_SQL)) {
            query.setString(1, sessionId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new StorageException(STORAGE_ERROR, e);
        }
    }

    public List<Entry> findBySession(String sessionId) {
        try (var conn = dataSource.getConnection();
             var query = conn.prepareStatement(FIND_SQL)) {
            query.setString(1, sessionId);
            try (var rows = query.executeQuery()) {
                var entries = new ArrayList<Entry>();
                while (rows.next()) {
                    entries.add(new Entry(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getInt(4), rows.getTimestamp(5).toLocalDateTime(),
                        rows.getTimestamp(6).toLocalDateTime()));
                }
                return entries;
            }
        } catch (SQLException e) {
            throw new StorageException(STORAGE_ERROR, e);
        }
    }

    public int deleteBySession(String sessionId) {
        try (var conn = dataSource.getConnection();
             var del = conn.prepareStatement(DELETE_BY_SESSION_SQL)) {
            del.setString(1, sessionId);
            return del.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException(STORAGE_ERROR, e);
        }
    }

    public int deleteOrphans() {
        int removed = 0;
        try (var conn = dataSource.getConnection()) {
            for (int batch = 0; batch < ORPHAN_MAX_BATCHES; batch++) {
                var ids = new ArrayList<Long>();
                try (var query = conn.prepareStatement(FIND_ORPHAN_IDS_SQL)) {
                    query.setInt(1, ORPHAN_BATCH_SIZE);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            ids.add(rows.getLong(1));
                        }
                    }
                }
                if (ids.isEmpty()) {
                    return removed;
                }
                var placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
                try (var del = conn.prepareStatement("DELETE FROM session_context WHERE id IN (" + placeholders + ")")) {
                    for (int i = 0; i < ids.size(); i++) {
                        del.setLong(i + 1, ids.get(i));
                    }
                    removed += del.executeUpdate();
                }
                if (ids.size() < ORPHAN_BATCH_SIZE) {
                    return removed;
                }
            }
            return removed;
        } catch (SQLException e) {
            throw new StorageException(STORAGE_ERROR, e);
        }
    }

    private <T> T inTransaction(TransactionWork<T> work) {
        try (var conn = dataSource.getConnection()) {
            var originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            Throwable failure = null;
            try {
                var outcome = work.run(conn);
                conn.commit();
                return outcome;
            } catch (SQLException | RuntimeException e) {
                failure = e;
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException restoreError) {
                    if (failure == null) {
                        throw new StorageException(STORAGE_ERROR, restoreError);
                    }
                    failure.addSuppressed(restoreError);
                }
            }
        } catch (SQLException e) {
            throw new StorageException(STORAGE_ERROR, e);
        }
    }

    private static String lockOwner(Connection conn, String sessionId) throws SQLException {
        try (var lock = conn.prepareStatement(LOCK_OWNER_SQL)) {
            lock.setString(1, sessionId);
            try (var rows = lock.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static LockedEntries lockEntries(Connection conn, String sessionId, String key) throws SQLException {
        int otherChars = 0;
        boolean keyExists = false;
        try (var lock = conn.prepareStatement(LOCK_ENTRIES_SQL)) {
            lock.setString(1, sessionId);
            try (var rows = lock.executeQuery()) {
                while (rows.next()) {
                    if (key.equals(rows.getString(1))) {
                        keyExists = true;
                    } else {
                        otherChars += rows.getInt(2);
                    }
                }
            }
        }
        return new LockedEntries(otherChars, keyExists);
    }

    private static void validate(String sessionId, String requester, String key, String title, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("requester is required");
        }
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("key must match [A-Za-z0-9._-]{1,64}");
        }
        if (title != null && title.length() > MAX_TITLE_CHARS) {
            throw new IllegalArgumentException("title must not exceed " + MAX_TITLE_CHARS + " characters");
        }
        if (title != null && (title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException("title must be a single line");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
    }
}
