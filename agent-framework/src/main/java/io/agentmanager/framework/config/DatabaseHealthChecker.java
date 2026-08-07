package io.agentmanager.framework.config;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 数据库启动检查：明确检查数据库是否存在，不存在时给出清晰的错误信息。
 * 表结构由框架自动创建（DDL auto），此处仅验证连接与库存在。
 */
@Component
public class DatabaseHealthChecker {
    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthChecker.class);

    private final DataSource dataSource;

    public DatabaseHealthChecker(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        log.info("Checking database connectivity...");
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT 1");
            }
            conn.commit();
            log.info("Database connection: OK");
        } catch (Exception e) {
            log.error("Database check failed: {}", e.getMessage());
            log.error("Required: CREATE DATABASE IF NOT EXISTS agent_manager_test;");
            log.error("And grant privileges: GRANT ALL PRIVILEGES ON agent_manager_test.* TO 'agent_manager'@'%';");
            throw new IllegalStateException("Database not ready", e);
        }
    }
}
