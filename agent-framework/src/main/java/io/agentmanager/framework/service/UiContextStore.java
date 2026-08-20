package io.agentmanager.framework.service;

import java.sql.Timestamp;
import java.util.Optional;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UI 交互上下文存储（MCP Apps 扩展 4.7：卡片交互静默更新模型上下文）。
 *
 * <p>规范 `ui/update-model-context` 语义：卡片交互结果只回 iframe 不进 LLM 上下文；
 * 本项目增强为静默更新——持久化后**下次 agent 调用时注入为 system context**（不触发新回复）。
 *
 * <p>存储：独立表 {@code ui_context}（与 agent_state 同库），session_id 为唯一键，
 * 每次更新**覆盖**上次（规范语义：each request overwrites the previous context）。
 * 写入用单行 UPSERT（MySQL 原子），读发生在 agent 调用前取最新值，无需显式加锁。
 *
 * <p>安全（4.7）：仅接受合法 sessionId（必须含 {@code tenant:thread} 分隔符），防跨租户注入。
 */
@Service
public class UiContextStore {
    private static final Logger log = LoggerFactory.getLogger(UiContextStore.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** sessionId 合法格式：tenant:thread（channel 链路 session key 形如 debug-user:xxx） */
    private static final String SESSION_SEPARATOR = ":";

    /**
     * 用户消息 metadata 中携带 sessionId 的键。
     * Controller 构建消息时写入，{@link UiContextInjectionHook} 在 PreCallEvent
     * 阶段读取并按该会话注入 ui_context（HarnessAgent 拒绝 inputMessages 中 SYSTEM 消息，
     * 官方唯一注入点是 Hook 的 appendSystemContent，见 UiContextInjectionHook）。
     */
    public static final String METADATA_SESSION_KEY = "uiContextSessionId";

    private final DataSource dataSource;

    public UiContextStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    /** 建表（幂等），失败 fail-fast（DB 不可用本就不该继续） */
    private void initSchema() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ui_context (
                    session_id         VARCHAR(255)  NOT NULL,
                    content            MEDIUMTEXT    NULL,
                    structured_context JSON          NULL,
                    updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (session_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            log.info("UiContextStore: ui_context table ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init ui_context table: " + e.getMessage(), e);
        }
    }

    /**
     * 校验 sessionId 格式（tenant:thread，两端非空；沿用 Channel 链路 session key 形状）。
     *
     * @throws IllegalArgumentException 格式非法（400）
     */
    public static String validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        var sep = sessionId.indexOf(SESSION_SEPARATOR);
        if (sep <= 0 || sep == sessionId.length() - 1) {
            throw new IllegalArgumentException("invalid sessionId format, expected 'tenant:thread': " + sessionId);
        }
        return sessionId;
    }

    /**
     * 覆盖式写入 UI 交互上下文（每次更新覆盖上次，规范语义）。
     *
     * @param content            卡片推送的 markdown 内容（可空）
     * @param structuredContent  结构化内容（可空；原样存为 JSON）
     */
    public void upsert(String sessionId, String content, Object structuredContent) {
        validateSessionId(sessionId);
        var structuredJson = structuredContent == null ? null : toJson(structuredContent);
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 INSERT INTO ui_context (session_id, content, structured_context, updated_at)
                 VALUES (?, ?, ?, NOW(3))
                 ON DUPLICATE KEY UPDATE
                   content = VALUES(content),
                   structured_context = VALUES(structured_context),
                   updated_at = NOW(3)
                 """)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, content == null || content.isBlank() ? null : content);
            stmt.setString(3, structuredJson);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.error("UiContextStore: upsert failed for {}: {}", sessionId, e.getMessage());
            throw new IllegalStateException("failed to persist ui context: " + e.getMessage(), e);
        }
    }

    /** 读取会话最新 UI 交互上下文（agent 调用前注入用） */
    public Optional<UiContext> findBySession(String sessionId) {
        validateSessionId(sessionId);
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT content, structured_context, updated_at FROM ui_context WHERE session_id = ?")) {
            stmt.setString(1, sessionId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new UiContext(
                rs.getString("content"),
                rs.getString("structured_context"),
                rs.getTimestamp("updated_at")
            ));
        } catch (Exception e) {
            log.error("UiContextStore: read failed for {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 渲染为注入用 system 消息文本（4.7：下次 agent 调用时注入为 system context）。
     * content / structuredContext 皆空时返回 null（不发消息）。
     */
    public static String renderInjectText(UiContext ctx) {
        if (ctx == null) {
            return null;
        }
        var content = ctx.content() == null ? "" : ctx.content().trim();
        var structured = ctx.structuredContext() == null ? "" : ctx.structuredContext().trim();
        if (content.isEmpty() && structured.isEmpty()) {
            return null;
        }
        var sb = new StringBuilder();
        sb.append("[UI 交互上下文] 来自 MCP 卡片交互，最后一次更新覆盖之前内容，供后续对话参考：");
        if (!content.isEmpty()) {
            sb.append("\n").append(content);
        }
        if (!structured.isEmpty()) {
            sb.append("\n\n结构化数据：\n").append(structured);
        }
        return sb.toString();
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("structuredContent is not JSON-serializable: " + e.getMessage(), e);
        }
    }

    /** 一条 UI 交互上下文（含更新时间） */
    public record UiContext(String content, String structuredContext, Timestamp updatedAt) {
    }
}