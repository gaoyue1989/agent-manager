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
 *   <li>confirm_context：TTL 过期未消费 → ConfirmContextStore.deleteExpired</li>
 *   <li>tool_audit_log：超过保留天数（默认 30 天）→ ToolAuditStore.deleteBefore（O3 审计仅保留元信息）</li>
 *   <li>agent_state / agent_fs：会话记录超期（默认 7 天）→ 既有 deleteBefore</li>
 *   <li>session_message：会话消息轨归档超期（与 agent_state 同 7 天语义）→ deleteBefore
 *       （docs/session-history-archive-design.md）</li>
 *   <li>session_user：会话-用户映射超期（默认 7 天，与 agent_state 对齐）→ SessionUserStore.deleteBefore</li>
 *   <li>Agent Protocol TaskRecord：终态记录超保留期（agent.agent-protocol.retention-days，默认 7 天）
 *       扫描 → scanExpiredProtocolTaskRecords（合成桶 agents/_agentscope_protocol/ 不在
 *       会话清理路径内，独立扫描；SDK 2.0.3 暂无删除 API，见方法注释）</li>
 * </ul>
 *
 * <p><b>session_event 不在这里清理。</b>它已迁到 Redis Streams，留存由 key TTL 承担
 * （写入时续期），本服务不再需要它——原先那条
 * {@code DELETE FROM session_event WHERE created_at < ?} 与 sessionEventStore 依赖一并下线。
 */
@Service
public class SessionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(SessionCleanupService.class);

    private final DataSource dataSource;
    private final SessionManager sessionManager;
    private final TurnLeaseStore turnLeaseStore;
    private final ConfirmContextStore confirmContextStore;
    private final ToolAuditStore toolAuditStore;
    private final SessionUserStore sessionUserStore;
    private final A2aAgentRefHolder agentRefHolder;
    private final io.agentmanager.framework.config.AgentManagerProperties props;
    private final io.agentmanager.framework.service.storage.FileStorage fileStorage;
    private final RemoteTaskRegistryStore remoteTaskRegistryStore;

    public SessionCleanupService(DataSource dataSource,
                                 SessionManager sessionManager,
                                 TurnLeaseStore turnLeaseStore,
                                 ConfirmContextStore confirmContextStore,
                                 ToolAuditStore toolAuditStore,
                                 SessionUserStore sessionUserStore,
                                 A2aAgentRefHolder agentRefHolder,
                                 io.agentmanager.framework.config.AgentManagerProperties props,
                                 io.agentmanager.framework.service.storage.FileStorage fileStorage,
                                 RemoteTaskRegistryStore remoteTaskRegistryStore) {
        this.dataSource = dataSource;
        this.sessionManager = sessionManager;
        this.turnLeaseStore = turnLeaseStore;
        this.confirmContextStore = confirmContextStore;
        this.toolAuditStore = toolAuditStore;
        this.sessionUserStore = sessionUserStore;
        this.agentRefHolder = agentRefHolder;
        this.props = props;
        this.fileStorage = fileStorage;
        this.remoteTaskRegistryStore = remoteTaskRegistryStore;
    }

    /**
     * 每天凌晨 3 点执行清理。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        log.info("Starting session cleanup...");

        // 1. 清理内存中的过期会话
        int memCleaned = sessionManager.cleanupExpired();

        // 2. 清理数据库层：turn_lease / confirm_context / tool_audit_log / remote_task_registry
        //    （session_event 已迁 Redis，留存由 key TTL 承担，不在这里清）
        turnLeaseStore.cleanupExpired();
        confirmContextStore.deleteExpired();
        toolAuditStore.deleteBefore(Instant.now().minus(toolAuditStore.retentionDays(), ChronoUnit.DAYS));
        // 在途登记终态行保留 7 天（设计 §18.2；与 TaskRecord 清理节奏一致）
        remoteTaskRegistryStore.deleteTerminalBefore(
            java.sql.Timestamp.from(Instant.now().minus(SESSION_RETENTION_DAYS, ChronoUnit.DAYS)));

        // 3. 清理会话记录（agent_state / agent_fs / session_message / session_user）
        Instant cutoff = Instant.now().minus(SESSION_RETENTION_DAYS, ChronoUnit.DAYS);
        int stateCleaned = deleteBefore("agent_state", cutoff);
        int fsCleaned = deleteBefore("agent_fs", cutoff);
        int messageCleaned = deleteBefore("session_message", cutoff);
        int userMappingCleaned = sessionUserStore.deleteBefore(cutoff);

        // 4. 清理过期上传文件（file-upload-download-plan §15-7）：
        //    超保留期（默认 7 天）且非 pending 状态的 upload 行 → 删行 + 删存储对象
        cleanupExpiredUploads();

        // 5. Agent Protocol 终态 TaskRecord 保留期扫描（协议关闭时为 no-op）
        scanExpiredProtocolTaskRecords();

        log.info("Session cleanup done: memory={}, agent_state={}, agent_fs={}, session_message={}, session_user={}",
            memCleaned, stateCleaned, fsCleaned, messageCleaned, userMappingCleaned);
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
     * Agent Protocol 终态 TaskRecord 保留期扫描（travel-fulfillment agent-protocol 设计 §7）。
     *
     * <p>合成桶 {@code agents/_agentscope_protocol/}（SDK 常量 PROTOCOL_AGENT_ID/PROTOCOL_SESSION_ID）
     * 不在任何会话清理路径内，需独立按 retention-days 扫描终态（COMPLETED/FAILED/CANCELLED，
     * {@code TaskStatus.isTerminal()}）且 last_updated 早于截止线的记录。
     *
     * <p><b>当前只扫描计数并留痕，不做物理删除</b>：SDK 2.0.3 对 TaskRecord 只提供
     * write/read/list（WorkspaceManager 无 deleteTaskRecord，ProtocolTaskRepository 仅
     * save/find，javap 实证），底层为整份任务映射文件的读改写（内部 ReentrantLock 串行），
     * 绕开 SDK 手改存储会与运行中任务的写入竞态。物理清理待 SDK 提供删除 API 后接入，
     * 届时本方法即挂接点。
     */
    void scanExpiredProtocolTaskRecords() {
        var protocol = props.agentProtocol();
        if (protocol == null || !protocol.enabled()) {
            return; // 协议未启用：无 TaskRecord 产生，直接跳过
        }
        try {
            var agent = agentRefHolder.get();
            if (agent == null) {
                log.warn("Agent Protocol TaskRecord 扫描跳过：当前无可用 HarnessAgent");
                return;
            }
            var records = agent.getWorkspaceManager().listTaskRecords(
                io.agentscope.core.agent.RuntimeContext.empty(),
                io.agentscope.extensions.agentprotocol.AgentProtocolConstants.PROTOCOL_AGENT_ID,
                io.agentscope.extensions.agentprotocol.AgentProtocolConstants.PROTOCOL_SESSION_ID);
            var cutoff = Instant.now().minus(protocol.retentionDays(), ChronoUnit.DAYS);
            var expired = records.stream()
                .filter(r -> r.getStatus() != null && r.getStatus().isTerminal())
                .filter(r -> lastUpdatedOf(r) != null && lastUpdatedOf(r).isBefore(cutoff))
                .map(io.agentscope.harness.agent.subagent.task.TaskRecord::getTaskId)
                .toList();
            if (!expired.isEmpty()) {
                log.info("Agent Protocol TaskRecord 扫描：{} 条终态记录超过保留期（{} 天），待 SDK 提供删除 API 后清理: {}",
                    expired.size(), protocol.retentionDays(), expired);
            }
        } catch (Exception e) {
            // 清理属兜底职责，失败不阻断主流程，次日重试
            log.warn("Agent Protocol TaskRecord 扫描失败: {}", e.getMessage());
        }
    }

    /** 记录时间基准：lastUpdatedAt 优先（终态转换时刷新），缺失回落 createdAt */
    private Instant lastUpdatedOf(io.agentscope.harness.agent.subagent.task.TaskRecord record) {
        return record.getLastUpdatedAt() != null ? record.getLastUpdatedAt() : record.getCreatedAt();
    }

    /** 允许 deleteBefore 清理的表名白名单（防 SQL 注入） */
    private static final java.util.Set<String> CLEANUP_TABLES = java.util.Set.of("agent_state", "agent_fs", "session_message");

    /**
     * 删除指定表中 updated_at 早于 cutoff 的记录。
     *
     * @return 删除行数；失败返回 0 并记录警告
     * @throws IllegalArgumentException 表名不在白名单中
     */
    private int deleteBefore(String table, Instant cutoff) {
        if (!CLEANUP_TABLES.contains(table)) {
            throw new IllegalArgumentException("Table not in cleanup whitelist: " + table);
        }
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