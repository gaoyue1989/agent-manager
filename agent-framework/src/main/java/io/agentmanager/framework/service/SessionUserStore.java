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
                  created_at  DATETIME(3)  NOT NULL,
                  updated_at  DATETIME(3)  NOT NULL,
                  KEY idx_user_id (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
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
     * 查询指定会话的标题（session_user.remark）。
     *
     * @param sessionId 会话 ID
     * @return 标题，不存在或为空时返回空串
     */
    public String findRemark(String sessionId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT remark FROM session_user WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                var remark = rs.getString("remark");
                return remark != null ? remark : "";
            }
        } catch (Exception e) {
            log.warn("SessionUserStore: findRemark failed for sid={}: {}", sessionId, e.getMessage());
        }
        return "";
    }

    /**
     * 更新会话标题（session_user.remark）；记录不存在时补建（user_id 回退已有值或 unknown）。
     *
     * @param sessionId 会话 ID
     * @param remark    标题
     * @return 是否写入成功
     */
    public boolean updateRemark(String sessionId, String remark) {
        // 先 UPDATE：行在会话发起时已由 upsert 写入。此处不能写
        // INSERT ... (SELECT ... FROM session_user) —— MySQL 1093 禁止在写目标表的同时读它。
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "UPDATE session_user SET remark = ?, updated_at = NOW(3) WHERE session_id = ?")) {
            ps.setString(1, remark);
            ps.setString(2, sessionId);
            if (ps.executeUpdate() > 0) {
                return true;
            }
        } catch (Exception e) {
            log.warn("SessionUserStore: updateRemark failed for sid={}: {}", sessionId, e.getMessage());
            return false;
        }
        // 行不存在（异常会话）时补建，user_id 回退 unknown
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "INSERT INTO session_user (session_id, user_id, remark, created_at, updated_at) "
                     + "VALUES (?, 'unknown', ?, NOW(3), NOW(3))")) {
            ps.setString(1, sessionId);
            ps.setString(2, remark);
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            log.warn("SessionUserStore: updateRemark insert failed for sid={}: {}", sessionId, e.getMessage());
            return false;
        }
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
     * 清理指定时间之前的记录（SessionCleanupService 调用）。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    public int deleteBefore(Instant cutoff) {
        try (var conn = dataSource.getConnection();
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
