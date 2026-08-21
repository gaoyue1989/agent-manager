package io.agentmanager.framework.service;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 工具调用轻量审计存储（tool_audit_log 表，无状态单次流架构 4.1.2）。
 *
 * <p>写入范围：仅工具类事件（TOOL_CALL_START / TOOL_CALL_END /
 * TOOL_RESULT_START / TOOL_RESULT_END），不含文本 delta 与参数累积 delta。
 * 审计粒度（O3 定稿）：仅元信息——何时、何工具、何状态，不落参数。
 *
 * <p>写入方式：异步批量（队列 + 定时合并），失败静默降级，不阻塞 SSE 直吐。
 * 保留期：默认 30 天，日级定时清理（SessionCleanupService + 本类兜底）。
 */
@Service
public class ToolAuditStore {
    private static final Logger log = LoggerFactory.getLogger(ToolAuditStore.class);

    /** 批量写间隔：每 100ms 合并一次 */
    private static final long FLUSH_INTERVAL_MS = 100;
    /** 单批最大条数 */
    private static final int BATCH_SIZE = 50;

    private final DataSource dataSource;
    private final int retentionDays;
    private final Deque<AuditEntry> queue = new ArrayDeque<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "tool-audit-flusher");
        t.setDaemon(true);
        return t;
    });

    public ToolAuditStore(DataSource dataSource) {
        this(dataSource, 30);
    }

    public ToolAuditStore(DataSource dataSource, int retentionDays) {
        this.dataSource = dataSource;
        this.retentionDays = retentionDays;
        initSchema();
        flushScheduled();
    }

    /** 建表（幂等），失败 fail-fast */
    private void initSchema() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tool_audit_log (
                  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                  session_id  VARCHAR(255) NOT NULL,
                  tool_name   VARCHAR(255) NOT NULL,
                  tool_call_id VARCHAR(64),
                  state       VARCHAR(32),
                  payload_json MEDIUMTEXT,
                  created_at  DATETIME(3) NOT NULL,
                  KEY idx_session (session_id, id),
                  KEY idx_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            log.info("ToolAuditStore: tool_audit_log table ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init tool_audit_log table: " + e.getMessage(), e);
        }
    }

    private void flushScheduled() {
        flusher.scheduleWithFixedDelay(this::flushQueue, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 异步入队（不阻塞 SSE）；幂等 */
    public void record(String sessionId, String toolName, String toolCallId, String state, String payloadJson) {
        synchronized (queue) {
            queue.addLast(new AuditEntry(sessionId, toolName, toolCallId, state, payloadJson, Instant.now()));
        }
    }

    /** 批量落库（定时触发；错误静默降级，避免影响主链路） */
    private void flushQueue() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Deque<AuditEntry> batch = new ArrayDeque<>();
            synchronized (queue) {
                while (!queue.isEmpty() && batch.size() < BATCH_SIZE) {
                    batch.addLast(queue.removeFirst());
                }
            }
            if (batch.isEmpty()) {
                return;
            }
            try (var conn = dataSource.getConnection();
                 var stmt = conn.prepareStatement("""
                     INSERT INTO tool_audit_log
                       (session_id, tool_name, tool_call_id, state, payload_json, created_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
                for (var e : batch) {
                    stmt.setString(1, e.sessionId());
                    stmt.setString(2, e.toolName());
                    stmt.setString(3, e.toolCallId());
                    stmt.setString(4, e.state());
                    stmt.setString(5, e.payloadJson());
                    stmt.setTimestamp(6, java.sql.Timestamp.from(e.createdAt()));
                    stmt.addBatch();
                }
                stmt.executeBatch();
            } catch (Exception e) {
                log.warn("ToolAuditStore: flush batch failed (discarded {}): {}", batch.size(), e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    /** 清理保留期之外的审计记录（默认 30 天） */
    public void deleteBefore(Instant cutoff) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM tool_audit_log WHERE created_at < ?")) {
            stmt.setTimestamp(1, java.sql.Timestamp.from(cutoff));
            var n = stmt.executeUpdate();
            if (n > 0) {
                log.info("ToolAuditStore: cleaned {} audit record(s) before {}", n, cutoff);
            }
        } catch (Exception e) {
            log.warn("ToolAuditStore: deleteBefore failed: {}", e.getMessage());
        }
    }

    public int retentionDays() {
        return retentionDays;
    }

    private record AuditEntry(
        String sessionId,
        String toolName,
        String toolCallId,
        String state,
        String payloadJson,
        Instant createdAt
    ) {
    }
}