package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turn 租约存储（turn_lease 表，无状态单次流架构 4.1.3）。
 *
 * <p>租约解决的是产品语义：同一 session 的活跃执行段并发会产生重复 turn，
 * 故以租约将执行段串行化。锁只覆盖活跃执行段（消息进入 → AGENT_END/error/
 * permission_ask 暂停点），不覆盖人工决策挂起期——HITL 暂停即让出锁，
 * 挂起期间新消息可直接 acquire 执行。
 *
 * <p>实现：租约 token + 短 TTL + 续租（不用 GET_LOCK，避免长 turn 耗尽连接池）。
 * 轮询为独立短连接，不占用连接池；崩溃由 TTL 过期兜底接管。
 */
@Service
public class TurnLeaseStore {
    private static final Logger log = LoggerFactory.getLogger(TurnLeaseStore.class);

    /** 轮询间隔：每 500ms 重试获取 */
    private static final long POLL_INTERVAL_MS = 500;

    private final DataSource dataSource;
    private final Duration ttl;
    private final Duration renewInterval;

    public TurnLeaseStore(DataSource dataSource) {
        this(dataSource, Duration.ofSeconds(60));
    }

    public TurnLeaseStore(DataSource dataSource, Duration ttl) {
        this(dataSource, ttl, ttl.dividedBy(3));
    }

    public TurnLeaseStore(DataSource dataSource, Duration ttl, Duration renewInterval) {
        this.dataSource = dataSource;
        this.ttl = ttl;
        this.renewInterval = renewInterval;
        initSchema();
    }

    /** 续租间隔（TurnLeaseGuard 使用；应小于 ttl，默认 ttl/3） */
    public Duration renewInterval() {
        return renewInterval;
    }

    /** 建表（幂等），失败 fail-fast（DB 不可用本就不该继续） */
    private void initSchema() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS turn_lease (
                  session_id VARCHAR(255) PRIMARY KEY,
                  token      CHAR(36) NOT NULL,
                  expires_at DATETIME(3) NOT NULL,
                  created_at DATETIME(3) NOT NULL,
                  KEY idx_expires_at (expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            log.info("TurnLeaseStore: turn_lease table ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init turn_lease table: " + e.getMessage(), e);
        }
    }

    /**
     * 等待式获取租约（排队语义）：尝试获取，未拿到则轮询等待直至超时。
     *
     * @return 租约 token；等待超时返回 null（上层返回 409 / error 帧兜底）
     */
    public String acquire(String sessionId, Duration waitTimeout) {
        long deadline = System.currentTimeMillis() + Math.max(0, waitTimeout.toMillis());
        do {
            var token = tryAcquire(sessionId);
            if (token != null) {
                return token;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    /**
     * 单次尝试获取租约（立即返回，不等待）。
     *
     * @return 租约 token；当前被其他执行段持有（未过期）时返回 null
     */
    public String tryAcquire(String sessionId) {
        var token = UUID.randomUUID().toString();
        try (var conn = dataSource.getConnection();
             var insert = conn.prepareStatement("""
                 INSERT INTO turn_lease (session_id, token, expires_at, created_at)
                 VALUES (?, ?, DATE_ADD(NOW(3), INTERVAL ? SECOND), NOW(3))
                 """)) {
            insert.setString(1, sessionId);
            insert.setString(2, token);
            insert.setInt(3, (int) ttl.toSeconds());
            insert.executeUpdate();
            return token;
        } catch (java.sql.SQLIntegrityConstraintViolationException pk) {
            // PK 冲突：该 session 已有租约行，检查是否已过期（前持有者崩溃）
            return takeoverIfExpired(sessionId);
        } catch (Exception e) {
            log.warn("TurnLeaseStore: tryAcquire failed for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** PK 冲突后的过期接管逻辑：过期 → 条件删除 → 重试插入；未过期返回 null（排队等待） */
    private String takeoverIfExpired(String sessionId) {
        try (var conn = dataSource.getConnection();
             var delete = conn.prepareStatement(
                 "DELETE FROM turn_lease WHERE session_id = ? AND expires_at < NOW(3)")) {
            delete.setString(1, sessionId);
            if (delete.executeUpdate() != 1) {
                // 未过期（其他执行段活跃）或被其他副本抢先接管 → 排队等待
                return null;
            }
            // 接管成功（DELETE 只影响 1 行，原子性由 PK 唯一约束保证）→ 重试插入
            return retryOnce(sessionId);
        } catch (Exception e) {
            log.warn("TurnLeaseStore: takeover failed for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 接管成功后的单次重试插入 */
    private String retryOnce(String sessionId) {
        var token = UUID.randomUUID().toString();
        try (var conn = dataSource.getConnection();
             var insert = conn.prepareStatement("""
                 INSERT INTO turn_lease (session_id, token, expires_at, created_at)
                 VALUES (?, ?, DATE_ADD(NOW(3), INTERVAL ? SECOND), NOW(3))
                 """)) {
            insert.setString(1, sessionId);
            insert.setString(2, token);
            insert.setInt(3, (int) ttl.toSeconds());
            insert.executeUpdate();
            return token;
        } catch (Exception e) {
            // 重试插入失败（被其他副本抢先）：等待下一轮
            return null;
        }
    }

    /**
     * 续租：延长租约 TTL。
     *
     * @return true 续租成功；false 租约已被接管/释放（停止续租的优雅退出信号）
     */
    public boolean renew(String sessionId, String token) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 UPDATE turn_lease SET expires_at = DATE_ADD(NOW(3), INTERVAL ? SECOND)
                 WHERE session_id = ? AND token = ?
                 """)) {
            stmt.setInt(1, (int) ttl.toSeconds());
            stmt.setString(2, sessionId);
            stmt.setString(3, token);
            return stmt.executeUpdate() == 1;
        } catch (Exception e) {
            log.warn("TurnLeaseStore: renew failed for {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /** 释放租约（token 校验防误删他人锁） */
    public void release(String sessionId, String token) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "DELETE FROM turn_lease WHERE session_id = ? AND token = ?")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, token);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("TurnLeaseStore: release failed for {}: {}", sessionId, e.getMessage());
        }
    }

    /** 清理已过期租约（崩溃兜底；正常路径由 TTL + release 覆盖） */
    public void cleanupExpired() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM turn_lease WHERE expires_at < NOW(3)")) {
            var n = stmt.executeUpdate();
            if (n > 0) {
                log.info("TurnLeaseStore: cleaned {} expired lease(s)", n);
            }
        } catch (Exception e) {
            log.warn("TurnLeaseStore: cleanupExpired failed: {}", e.getMessage());
        }
    }
}