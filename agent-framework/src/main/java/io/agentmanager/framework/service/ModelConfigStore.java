package io.agentmanager.framework.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 托管模型配置持久化（model_config 表）——会话可切换模型的配置来源。
 *
 * <p>与系统模型（LLM_* 环境变量，只读）并列：本表存运行期经 REST CRUD 的备选模型，
 * 由 {@link ModelCatalog} 装配为 Model 实例供会话路由使用（设计见
 * docs/session-model-switch-design.md）。
 *
 * <p><b>api_key 为明文列</b>（内网库，决策#1）：不参与任何日志输出；读接口一律掩码展示。
 * 留空 = 运行时回落系统模型 LLM_API_KEY（同端点多模型场景）。
 *
 * <p>写操作失败直接抛 {@link IllegalStateException}（配置管理接口需要如实报错，不做静默吞异常）；
 * 读操作同样抛出，由调用方决定降级策略（ModelCatalog.resolve 对会话链路做 fail-soft 回落默认模型）。
 */
@Service
public class ModelConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigStore.class);

    /** 托管模型配置（model_config 一行） */
    public record ModelConfig(
        String id,
        String name,
        String provider,
        String modelId,
        String baseUrl,
        String apiKey,
        double temperature,
        int maxTokens,
        int timeoutSeconds,
        boolean enableThinking,
        int contextLength,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
    ) {}

    private static final String COLUMNS =
        "id, name, provider, model_id, base_url, api_key, temperature, max_tokens, "
            + "timeout_seconds, enable_thinking, context_length, enabled, created_at, updated_at";

    private final DataSource dataSource;

    public ModelConfigStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS model_config (
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
            log.info("ModelConfigStore: model_config table ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init model_config table: " + e.getMessage(), e);
        }
    }

    /** 全部托管模型（管理视图：含 disabled），按创建时间升序 */
    public List<ModelConfig> listAll() {
        return list("SELECT " + COLUMNS + " FROM model_config ORDER BY created_at");
    }

    /** 启用的托管模型（会话可选列表：GET /models） */
    public List<ModelConfig> listEnabled() {
        return list("SELECT " + COLUMNS + " FROM model_config WHERE enabled = 1 ORDER BY created_at");
    }

    public Optional<ModelConfig> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM model_config WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("model_config lookup failed (id=" + id + "): " + e.getMessage(), e);
        }
    }

    /** 按展示名查（唯一性预检）：新建/改名时判重 */
    public Optional<ModelConfig> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM model_config WHERE name = ?")) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("model_config name lookup failed: " + e.getMessage(), e);
        }
    }

    public void insert(ModelConfig cfg) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                 INSERT INTO model_config (id, name, provider, model_id, base_url, api_key, temperature,
                     max_tokens, timeout_seconds, enable_thinking, context_length, enabled,
                     created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                 """)) {
            ps.setString(1, cfg.id());
            ps.setString(2, cfg.name());
            ps.setString(3, cfg.provider());
            ps.setString(4, cfg.modelId());
            ps.setString(5, cfg.baseUrl());
            ps.setString(6, cfg.apiKey());
            ps.setDouble(7, cfg.temperature());
            ps.setInt(8, cfg.maxTokens());
            ps.setInt(9, cfg.timeoutSeconds());
            ps.setBoolean(10, cfg.enableThinking());
            ps.setInt(11, cfg.contextLength());
            ps.setBoolean(12, cfg.enabled());
            ps.executeUpdate();
            log.info("model_config created: id={}, name={}, modelId={}", cfg.id(), cfg.name(), cfg.modelId());
        } catch (Exception e) {
            throw new IllegalStateException("model_config insert failed (name=" + cfg.name() + "): "
                + e.getMessage(), e);
        }
    }

    /** 全量更新（api_key 以传入值为准；上层用掩码==原值判断"未修改"并回填） */
    public boolean update(ModelConfig cfg) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                 UPDATE model_config SET name = ?, provider = ?, model_id = ?, base_url = ?, api_key = ?,
                     temperature = ?, max_tokens = ?, timeout_seconds = ?, enable_thinking = ?,
                     context_length = ?, enabled = ?, updated_at = NOW(3)
                 WHERE id = ?
                 """)) {
            // 参数顺序与 bindAll 不同：UPDATE 以 name 起始，WHERE id 收尾
            ps.setString(1, cfg.name());
            ps.setString(2, cfg.provider());
            ps.setString(3, cfg.modelId());
            ps.setString(4, cfg.baseUrl());
            ps.setString(5, cfg.apiKey());
            ps.setDouble(6, cfg.temperature());
            ps.setInt(7, cfg.maxTokens());
            ps.setInt(8, cfg.timeoutSeconds());
            ps.setBoolean(9, cfg.enableThinking());
            ps.setInt(10, cfg.contextLength());
            ps.setBoolean(11, cfg.enabled());
            ps.setString(12, cfg.id());
            var n = ps.executeUpdate() > 0;
            if (n) {
                log.info("model_config updated: id={}, name={}", cfg.id(), cfg.name());
            }
            return n;
        } catch (Exception e) {
            throw new IllegalStateException("model_config update failed (id=" + cfg.id() + "): "
                + e.getMessage(), e);
        }
    }

    public boolean deleteById(String id) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM model_config WHERE id = ?")) {
            ps.setString(1, id);
            var n = ps.executeUpdate() > 0;
            if (n) {
                log.info("model_config deleted: id={}", id);
            }
            return n;
        } catch (Exception e) {
            throw new IllegalStateException("model_config delete failed (id=" + id + "): "
                + e.getMessage(), e);
        }
    }

    private List<ModelConfig> list(String sql) {
        var result = new ArrayList<ModelConfig>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
        } catch (Exception e) {
            throw new IllegalStateException("model_config list failed: " + e.getMessage(), e);
        }
        return result;
    }

    /** model_config 行 → ModelConfig */
    private ModelConfig map(ResultSet rs) throws SQLException {
        return new ModelConfig(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("provider"),
            rs.getString("model_id"),
            rs.getString("base_url"),
            rs.getString("api_key"),
            rs.getDouble("temperature"),
            rs.getInt("max_tokens"),
            rs.getInt("timeout_seconds"),
            rs.getBoolean("enable_thinking"),
            rs.getInt("context_length"),
            rs.getBoolean("enabled"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(java.sql.Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
