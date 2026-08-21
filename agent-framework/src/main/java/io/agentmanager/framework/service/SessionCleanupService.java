package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
 *   <li>confirm_context：TTL 过期未消费 → ConfirmContextStore.deleteExpired</li>
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
    private final ConfirmContextStore confirmContextStore;
    private final ToolAuditStore toolAuditStore;

    public SessionCleanupService(DataSource dataSource,
                                 SessionManager sessionManager,
                                 TurnLeaseStore turnLeaseStore,
                                 ConfirmContextStore confirmContextStore,
                                 ToolAuditStore toolAuditStore) {
        this.dataSource = dataSource;
        this.sessionManager = sessionManager;
        this.turnLeaseStore = turnLeaseStore;
        this.confirmContextStore = confirmContextStore;
        this.toolAuditStore = toolAuditStore;
    }

    /**
     * 每天凌晨 3 点执行清理。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        log.info("Starting session cleanup...");

        // 1. 清理内存中的过期会话
        int memCleaned = sessionManager.cleanupExpired();

        // 2. 清理数据库层：turn_lease / confirm_context / tool_audit_log
        turnLeaseStore.cleanupExpired();
        confirmContextStore.deleteExpired();
        toolAuditStore.deleteBefore(Instant.now().minus(toolAuditStore.retentionDays(), ChronoUnit.DAYS));

        // 3. 清理会话记录（agent_state / agent_fs）
        Instant cutoff = Instant.now().minus(SESSION_RETENTION_DAYS, ChronoUnit.DAYS);
        int stateCleaned = deleteBefore("agent_state", cutoff);
        int fsCleaned = deleteBefore("agent_fs", cutoff);

        log.info("Session cleanup done: memory={}, agent_state={}, agent_fs={}",
            memCleaned, stateCleaned, fsCleaned);
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