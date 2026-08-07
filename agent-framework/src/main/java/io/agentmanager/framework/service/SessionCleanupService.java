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
 * 会话定时清理：清理内存中的过期会话 + 数据库中的过期记录。
 * 每天凌晨 3 点执行。
 */
@Service
public class SessionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(SessionCleanupService.class);

    /** 数据库会话记录保留天数：7 天 */
    private static final int RETENTION_DAYS = 7;

    private final DataSource dataSource;
    private final SessionManager sessionManager;

    public SessionCleanupService(DataSource dataSource, SessionManager sessionManager) {
        this.dataSource = dataSource;
        this.sessionManager = sessionManager;
    }

    /**
     * 每天凌晨 3 点执行清理。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        log.info("Starting session cleanup...");

        // 1. 清理内存中的过期会话
        int memCleaned = sessionManager.cleanupExpired();

        // 2. 清理数据库中的过期记录
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int stateCleaned = deleteBefore("agent_state", cutoff);
        int fsCleaned = deleteBefore("agent_fs", cutoff);

        log.info("Session cleanup done: memory={}, agent_state={}, agent_fs={}",
            memCleaned, stateCleaned, fsCleaned);
    }

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
