package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

public class SessionContextStore {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_TITLE_CHARS = 255;
    private static final String STORAGE_ERROR = "session context store unavailable";

    private static final String CLAIM_SESSION_SQL = """
        INSERT INTO session_user (session_id, user_id, created_at, updated_at)
        VALUES (?, ?, NOW(3), NOW(3))
        ON DUPLICATE KEY UPDATE session_id = session_id
        """;

    private static final String LOCK_OWNER_SQL = """
        SELECT user_id FROM session_user WHERE session_id = ? FOR UPDATE
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

    private static final String DELETE_ORPHANS_SQL = """
        DELETE c FROM session_context c
        LEFT JOIN session_user u ON u.session_id = c.session_id
        WHERE u.session_id IS NULL
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

    public static class StorageException extends RuntimeException {

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
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
        try (var conn = dataSource.getConnection()) {
            var originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            UpsertOutcome outcome;
            try {
                outcome = writeLocked(conn, sessionId, requester, key, effectiveTitle, content);
                if (outcome instanceof UpsertOutcome.Accepted) {
                    conn.commit();
                } else {
                    conn.rollback();
                }
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
            return outcome;
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

    public int deleteByKey(String sessionId, String key) {
        try (var conn = dataSource.getConnection();
             var del = conn.prepareStatement(DELETE_BY_KEY_SQL)) {
            del.setString(1, sessionId);
            del.setString(2, key);
            return del.executeUpdate();
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
        try (var conn = dataSource.getConnection();
             var del = conn.prepareStatement(DELETE_ORPHANS_SQL)) {
            return del.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException(STORAGE_ERROR, e);
        }
    }

    private UpsertOutcome writeLocked(Connection conn, String sessionId, String requester,
                                      String key, String title, String content) throws SQLException {
        try (var claim = conn.prepareStatement(CLAIM_SESSION_SQL)) {
            claim.setString(1, sessionId);
            claim.setString(2, requester);
            claim.executeUpdate();
        }
        String owner;
        try (var lock = conn.prepareStatement(LOCK_OWNER_SQL)) {
            lock.setString(1, sessionId);
            try (var rows = lock.executeQuery()) {
                owner = rows.next() ? rows.getString(1) : null;
            }
        }
        if (!SessionAccessGuard.isOwner(owner, requester)) {
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
            return new UpsertOutcome.BudgetExceeded(content.length(), totalChars, maxTotalChars);
        }
        try (var write = conn.prepareStatement(WRITE_SQL)) {
            write.setString(1, sessionId);
            write.setString(2, key);
            write.setString(3, title);
            write.setString(4, content);
            write.setInt(5, content.length());
            write.setString(6, requester);
            write.executeUpdate();
        }
        return new UpsertOutcome.Accepted(key, title, content.length(), totalChars, maxTotalChars,
            !locked.keyExists());
    }

    private LockedEntries lockEntries(Connection conn, String sessionId, String key) throws SQLException {
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
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
    }
}
