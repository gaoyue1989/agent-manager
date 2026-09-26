package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.mysql.cj.jdbc.MysqlDataSource;

/**
 * {@link ModelConfigStore} 集成测试（真实 MySQL，运行方式与其它 MySQL IT 同款开关）：
 * <pre>
 * HITL_MYSQL_IT=1 CHECKPOINT_JDBC_URL=jdbc:mysql://127.0.0.1:3307/agent_manager_test \
 *   CHECKPOINT_USERNAME=agent_manager CHECKPOINT_PASSWORD=... mvn test -Dtest=ModelConfigStoreMySqlIT
 * </pre>
 *
 * <p><b>覆盖动机（2026-09-27 review 暴露）</b>：model_config 表演进（reasoning_effort /
 * frequency_penalty 补列）全部在 {@code initSchema/ensureColumn} 里，而单测对 store 一律 mock，
 * ensureColumn 的参数绑定顺序、ALTER 竞态容错、新列 NULL 读写只有真实 MySQL 能验证。
 */
@EnabledIfEnvironmentVariable(named = "HITL_MYSQL_IT", matches = "1")
class ModelConfigStoreMySqlIT {

    @Test
    void freshSchemaShouldRoundTripSamplingParamsAndStayIdempotent() throws Exception {
        var dataSource = dataSource();
        dropTable(dataSource);
        // 全新建表（含新列）
        var store = new ModelConfigStore(dataSource);
        // 二次构造 = 二次启动：ensureColumn 必须幂等（列已存在 → 不再 ALTER，且不抛异常）
        var storeAgain = new ModelConfigStore(dataSource);

        var id = "it-" + UUID.randomUUID();
        try {
            store.insert(new ModelConfigStore.ModelConfig(id, "IT全量", "vllm", "qwen3-32b",
                "http://vllm:8000/v1", null, 0.2, 4096, 60, false, "medium", 0.5, 0, true,
                Instant.now(), Instant.now()));

            var loaded = store.findById(id).orElseThrow();
            assertEquals("vllm", loaded.provider());
            assertEquals("medium", loaded.reasoningEffort());
            assertEquals(0.5, loaded.frequencyPenalty());

            // 清除 effort（null）+ freq 改值：UPDATE 全量写 NULL 列后读取仍是 null
            assertTrue(store.update(new ModelConfigStore.ModelConfig(loaded.id(), loaded.name(),
                loaded.provider(), loaded.modelId(), loaded.baseUrl(), loaded.apiKey(),
                loaded.temperature(), loaded.maxTokens(), loaded.timeoutSeconds(),
                loaded.enableThinking(), null, 0.0, loaded.contextLength(), loaded.enabled(),
                loaded.createdAt(), loaded.updatedAt())));
            var reloaded = storeAgain.findById(id).orElseThrow();
            assertNull(reloaded.reasoningEffort());
            assertEquals(0.0, reloaded.frequencyPenalty());
        } finally {
            deleteRow(dataSource, id);
        }
    }

    @Test
    void legacySchemaShouldAutoMigrateAndReadNullSamplingParams() throws Exception {
        var dataSource = dataSource();
        dropTable(dataSource);
        // 模拟存量旧表（无两新列，含一条旧行）
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE model_config (
                  id              VARCHAR(64)   NOT NULL PRIMARY KEY,
                  name            VARCHAR(128)  NOT NULL,
                  provider        VARCHAR(32)   NOT NULL DEFAULT 'openai',
                  model_id        VARCHAR(128)  NOT NULL,
                  base_url        VARCHAR(512)  NOT NULL,
                  api_key         VARCHAR(512)  DEFAULT NULL,
                  temperature     DOUBLE        NOT NULL DEFAULT 0.3,
                  max_tokens      INT           NOT NULL DEFAULT 16384,
                  timeout_seconds INT           NOT NULL DEFAULT 120,
                  enable_thinking TINYINT(1)    NOT NULL DEFAULT 0,
                  context_length  INT           NOT NULL DEFAULT 0,
                  enabled         TINYINT(1)    NOT NULL DEFAULT 1,
                  created_at      DATETIME(3)   NOT NULL,
                  updated_at      DATETIME(3)   NOT NULL,
                  UNIQUE KEY uk_model_name (name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            stmt.executeUpdate("""
                INSERT INTO model_config (id, name, provider, model_id, base_url, temperature,
                    max_tokens, timeout_seconds, enable_thinking, context_length, enabled,
                    created_at, updated_at)
                VALUES ('legacy-1', '旧行', 'openai', 'old-model', 'http://old/v1',
                    0.3, 16384, 120, 0, 0, 1, NOW(3), NOW(3))
                """);
        }

        // 启动即迁移：缺列自动 ALTER 补齐
        var store = new ModelConfigStore(dataSource);
        assertEquals("reasoning_effort", columnNameOf(dataSource, "reasoning_effort"));
        assertEquals("frequency_penalty", columnNameOf(dataSource, "frequency_penalty"));

        try {
            // 旧行两新列为 NULL → 不下发语义
            var legacy = store.findById("legacy-1").orElseThrow();
            assertNull(legacy.reasoningEffort());
            assertNull(legacy.frequencyPenalty());

            // 迁移后表可正常承载新配置写入
            store.insert(new ModelConfigStore.ModelConfig("new-1", "新行", "sglang",
                "glm5", "http://sg:8000/v1", null, 0.3, 8192, 60, true, "high", null, 0, true,
                Instant.now(), Instant.now()));
            assertEquals("high", store.findById("new-1").orElseThrow().reasoningEffort());
        } finally {
            deleteRow(dataSource, "legacy-1");
            deleteRow(dataSource, "new-1");
        }
    }

    // ===== helpers =====

    private static MysqlDataSource dataSource() {
        var ds = new MysqlDataSource();
        ds.setURL(System.getenv("CHECKPOINT_JDBC_URL"));
        ds.setUser(System.getenv("CHECKPOINT_USERNAME"));
        ds.setPassword(System.getenv("CHECKPOINT_PASSWORD"));
        return ds;
    }

    private static void dropTable(MysqlDataSource ds) throws Exception {
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP TABLE IF EXISTS model_config");
        }
    }

    private static void deleteRow(MysqlDataSource ds, String id) {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement("DELETE FROM model_config WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // IT 清理失败不影响断言
        }
    }

    /** 列存在性检查：返回列名（不存在为 null） */
    private static String columnNameOf(MysqlDataSource ds, String column) throws Exception {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT column_name FROM information_schema.COLUMNS "
                     + "WHERE table_schema = DATABASE() AND table_name = 'model_config' AND column_name = ?")) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
