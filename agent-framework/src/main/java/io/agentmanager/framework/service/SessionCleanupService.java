package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 会话与租约定时清理：每天凌晨 3 点执行（无状态单次流架构，见 stateless-single-stream-plan O2）。
 *
 * <p>清理范围（三表联动）：
 * <ul>
 *   <li>turn_lease：已过期租约（崩溃兜底；正常路径由 release + TTL 覆盖）→ TurnLeaseStore.cleanupExpired</li>
 *   <li>tool_audit_log：超过保留天数（默认 30 天）→ ToolAuditStore.deleteBefore（O3 审计仅保留元信息）</li>
 *   <li>agent_state / agent_fs：会话记录超期（默认 7 天）→ 既有 deleteBefore</li>
 * </ul>
 */
@Service
public class SessionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(SessionCleanupService.class);

    private final DataSource dataSource;
    private final SessionManager sessionManager;
    private final TurnLeaseStore turnLeaseStore;
    private final ToolAuditStore toolAuditStore;
    private final io.agentmanager.framework.config.AgentManagerProperties props;
    private final io.agentmanager.framework.service.storage.FileStorage fileStorage;
    private final AguiInterruptStore aguiInterruptStore;

    public SessionCleanupService(DataSource dataSource,
                                 SessionManager sessionManager,
                                 TurnLeaseStore turnLeaseStore,
                                 ToolAuditStore toolAuditStore,
                                 io.agentmanager.framework.config.AgentManagerProperties props,
                                 io.agentmanager.framework.service.storage.FileStorage fileStorage,
                                 AguiInterruptStore aguiInterruptStore) {
        this.dataSource = dataSource;
        this.sessionManager = sessionManager;
        this.turnLeaseStore = turnLeaseStore;
        this.toolAuditStore = toolAuditStore;
        this.props = props;
        this.fileStorage = fileStorage;
        this.aguiInterruptStore = aguiInterruptStore;
    }

    /**
     * 每天凌晨 3 点执行清理。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        log.info("Starting session cleanup...");

        // 1. 清理内存中的过期会话
        int memCleaned = sessionManager.cleanupExpired();

        // 2. 清理数据库层：turn_lease / tool_audit_log / agui_interrupt
        turnLeaseStore.cleanupExpired();
        toolAuditStore.deleteBefore(Instant.now().minus(toolAuditStore.retentionDays(), ChronoUnit.DAYS));
        int aguiCleaned = aguiInterruptStore.deleteExpired();

        // 3. 清理会话记录（agent_state / agent_fs）
        Instant cutoff = Instant.now().minus(SESSION_RETENTION_DAYS, ChronoUnit.DAYS);
        int stateCleaned = deleteBefore("agent_state", cutoff);
        int fsCleaned = deleteBefore("agent_fs", cutoff);

        // 4. 清理过期上传文件（file-upload-download-plan §15-7）：
        //    超保留期（默认 7 天）且非 pending 状态的 upload 行 → 删行 + 删存储对象
        cleanupExpiredUploads();

        log.info("Session cleanup done: memory={}, agent_state={}, agent_fs={}, agui_interrupt={}",
            memCleaned, stateCleaned, fsCleaned, aguiCleaned);
    }

    /** 过期上传文件清理：删 DB 行 + 删存储对象（先删行后删对象，对象删除失败仅告警可重试） */
    private void cleanupExpiredUploads() {
        try {
            var cutoff = java.time.LocalDateTime.now().minusDays(props.file().retentionDays());
            List<String> keys = new java.util.ArrayList<>();
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                     "SELECT id, storage_key FROM file_asset WHERE origin = 'upload' "
                         + "AND status != 'pending' AND created_at < ?")) {
                ps.setTimestamp(1, java.sql.Timestamp.valueOf(cutoff));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.add(rs.getString("id") + "|" + rs.getString("storage_key"));
                    }
                }
            }
            for (var entry : keys) {
                var parts = entry.split("\\|", 2);
                var id = parts[0];
                var storageKey = parts.length > 1 ? parts[1] : null;
                // 先删行
                try (var conn = dataSource.getConnection();
                     var ps = conn.prepareStatement("DELETE FROM file_asset WHERE id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                // 后删对象（失败仅告警，下次清扫重试；孤儿清扫任务 P2 兜底）
                if (storageKey != null && !storageKey.isBlank()) {
                    try {
                        fileStorage.delete(storageKey);
                    } catch (Exception e) {
                        log.warn("cleanup: storage object delete failed ({}): {}", storageKey, e.getMessage());
                    }
                }
            }
            if (!keys.isEmpty()) {
                log.info("cleanup: removed {} expired upload file(s)", keys.size());
            }
        } catch (Exception e) {
            log.warn("cleanup: expired upload cleanup failed: {}", e.getMessage());
        }
    }

    /** 会话记录保留天数（默认 7 天，保持与配置对齐的语义默认值） */
    private static final int SESSION_RETENTION_DAYS = 7;

    /**
     * 删除指定表中 updated_at 早于 cutoff 的记录。
     *
     * @return 删除行数；失败返回 0 并记录警告
     */
    private int deleteBefore(String table, Instant cutoff) {
        String sql = "DELETE FROM " + table + " WHERE updated_at < ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(cutoff));
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Failed to cleanup table {}: {}", table, e.getMessage());
            return 0;
        }
    }
}