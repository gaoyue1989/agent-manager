package io.agentmanager.framework.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话-用户映射持久化（session_user 表）。
 *
 * <p>记录每个 session 归属的 userId，供 GET /threads?userId=xxx 按用户过滤。
 * 在会话首次发起（chat / confirm）时 upsert 写入，与 agent_state 生命周期解耦。
 */
@Service
public class SessionUserStore {

    private static final Logger log = LoggerFactory.getLogger(SessionUserStore.class);

    /** 允许经 {@link #upsertColumn}/{@link #findColumnBySession} 读写的列白名单（防 SQL 拼接注入） */
    private static final java.util.Set<String> UPDATABLE_COLUMNS = java.util.Set.of("model", "remark");

    private final DataSource dataSource;

    public SessionUserStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS session_user (
                  session_id  VARCHAR(255) NOT NULL PRIMARY KEY,
                  user_id     VARCHAR(255) NOT NULL,
                  remark      VARCHAR(512) DEFAULT '',
                  model       VARCHAR(128) DEFAULT '',
                  created_at  DATETIME(3)  NOT NULL,
                  updated_at  DATETIME(3)  NOT NULL,
                  KEY idx_user_id (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            // 历史表结构演进（CREATE TABLE IF NOT EXISTS 不会给旧表补列）
            ensureColumn("remark", "remark VARCHAR(512) DEFAULT '' AFTER user_id");
            ensureColumn("model", "model VARCHAR(128) DEFAULT '' AFTER remark");
            log.info("SessionUserStore: session_user table ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init session_user table: " + e.getMessage(), e);
        }
    }

    /**
     * 记录或更新会话归属用户。
     *
     * @param sessionId 会话 ID（与 agent_state.session_id 一致）
     * @param userId    用户 ID
     */
    public void upsert(String sessionId, String userId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                INSERT INTO session_user (session_id, user_id, created_at, updated_at)
                VALUES (?, ?, NOW(3), NOW(3))
                ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), updated_at = NOW(3)
                """)) {
            ps.setString(1, sessionId);
            ps.setString(2, userId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("SessionUserStore: upsert failed for sid={}, uid={}: {}", sessionId, userId, e.getMessage());
        }
    }

    /**
     * 查询指定用户的所有会话 ID。
     *
     * @param userId 用户 ID
     * @return 该用户拥有的 session_id 列表（按更新时间倒序）
     */
    public List<String> findSessionIdsByUser(String userId) {
        var result = new ArrayList<String>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT session_id FROM session_user WHERE user_id = ? ORDER BY updated_at DESC")) {
            ps.setString(1, userId);
            var rs = ps.executeQuery();
            while (rs.next()) {
                result.add(rs.getString("session_id"));
            }
        } catch (Exception e) {
            log.warn("SessionUserStore: findSessionIdsByUser failed for uid={}: {}", userId, e.getMessage());
        }
        return result;
    }

    /**
     * 查询指定会话的用户 ID。
     *
     * @param sessionId 会话 ID
     * @return 用户 ID，不存在时返回 null
     */
    public String findUserIdBySession(String sessionId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT user_id FROM session_user WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("user_id");
            }
        } catch (Exception e) {
            log.warn("SessionUserStore: findUserIdBySession failed for sid={}: {}", sessionId, e.getMessage());
        }
        return null;
    }

    /**
     * 设置会话绑定的模型（会话级模型切换，见 docs/session-model-switch-design.md）。
     * 空串 = 清除覆盖，回到默认（系统）模型。
     *
     * <p>实现说明（不可退回旧写法）：UPDATE 优先、0 行时回落 INSERT。
     * 原实现（含 ThreadController 早期版本）用 {@code INSERT ... VALUES (?, COALESCE((SELECT user_id
     * FROM session_user WHERE session_id = ?), 'unknown'), ...)} 取用户列——MySQL 8 对
     * "VALUES 子查询引用目标表" 直接报 ERROR 1093（You can't specify target table for update
     * in FROM clause），导致模型绑定/标题写入**静默失败**（异常被 catch 成 warn）。
     *
     * @param sessionId 会话 ID
     * @param modelId   托管模型 id（model_config.id）或空串
     */
    public void upsertModel(String sessionId, String modelId) {
        upsertColumn(sessionId, "model", modelId != null ? modelId : "");
    }

    /**
     * 查询会话绑定的模型（SessionModelMiddleware 每次模型调用实时读取，多副本强一致）。
     *
     * @return 托管模型 id；未设置/查询失败返回空串（调用方回落默认模型）
     */
    public String findModelBySession(String sessionId) {
        return findColumnBySession(sessionId, "model");
    }

    /** 写入会话标题（session_user.remark）；不存在时补建行（实现说明见 {@link #upsertModel}） */
    public void upsertRemark(String sessionId, String title) {
        upsertColumn(sessionId, "remark", title);
    }

    /**
     * 查询会话标题（remark）。
     *
     * @return 标题；不存在/未设置/查询失败返回空串
     */
    public String findRemarkBySession(String sessionId) {
        return findColumnBySession(sessionId, "remark");
    }

    /**
     * 单列 upsert（model / remark 共用；列名仅接受本类白名单常量，非用户输入）。
     * UPDATE 命中即返回；0 行（会话行不存在，如 PATCH 打向未知会话）补建行。
     */
    private void upsertColumn(String sessionId, String column, String value) {
        if (!UPDATABLE_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("column not updatable: " + column);
        }
        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement(
                    "UPDATE session_user SET " + column + " = ?, updated_at = NOW(3) WHERE session_id = ?")) {
                ps.setString(1, value);
                ps.setString(2, sessionId);
                if (ps.executeUpdate() > 0) {
                    return;
                }
            }
            // 补建行：ON DUPLICATE KEY 兜住并发插入竞态（此时以已存在行为准，仅补列值）
            try (var ps = conn.prepareStatement(
                    "INSERT INTO session_user (session_id, user_id, " + column + ", created_at, updated_at) "
                        + "VALUES (?, 'unknown', ?, NOW(3), NOW(3)) "
                        + "ON DUPLICATE KEY UPDATE " + column + " = VALUES(" + column + "), updated_at = NOW(3)")) {
                ps.setString(1, sessionId);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("SessionUserStore: upsert {} failed for sid={}: {}", column, sessionId, e.getMessage());
        }
    }

    /** 单列读取（model / remark 共用；列名白名单同上） */
    private String findColumnBySession(String sessionId, String column) {
        if (!UPDATABLE_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("column not queryable: " + column);
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT " + column + " FROM session_user WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                var v = rs.getString(column);
                return v != null ? v : "";
            }
        } catch (Exception e) {
            log.warn("SessionUserStore: find {} failed for sid={}: {}", column, sessionId, e.getMessage());
        }
        return "";
    }

    /** 幂等确保列存在（历史表结构演进，ALREADY 存在时跳过） */
    private void ensureColumn(String column, String ddl) {
        try (var conn = dataSource.getConnection();
             var rs = conn.getMetaData().getColumns(null, null, "session_user", column)) {
            if (!rs.next()) {
                try (var stmt = conn.createStatement()) {
                    stmt.executeUpdate("ALTER TABLE session_user ADD COLUMN " + ddl);
                    log.info("SessionUserStore: added '{}' column to session_user table", column);
                }
            }
        } catch (Exception e) {
            // 与 ThreadController.ensureRemarkColumn 同口径：探测失败不阻断启动（列已存在时 ALTER 也不会执行）
            log.debug("SessionUserStore: ensureColumn '{}' check skipped: {}", column, e.getMessage());
        }
    }

    /**
     * 清理指定时间之前的记录（SessionCleanupService 调用）。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    public int deleteBefore(Instant cutoff) {        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "DELETE FROM session_user WHERE updated_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            var n = ps.executeUpdate();
            if (n > 0) {
                log.info("SessionUserStore: cleaned {} record(s) before {}", n, cutoff);
            }
            return n;
        } catch (Exception e) {
            log.warn("SessionUserStore: deleteBefore failed: {}", e.getMessage());
            return 0;
        }
    }
}
