package io.agentmanager.framework.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.service.AgentRuntimeService.ConfirmAlreadyConsumedException;
import io.agentmanager.framework.service.AgentRuntimeService.ConfirmContextNotFoundException;
import io.agentscope.core.message.ToolUseBlock;

/**
 * HITL 确认上下文存储（confirm_context 表，无状态单次流架构 4.1.1）。
 *
 * <p>人工确认场景下将确认上下文落库（跨副本可见），任意副本读取消费；
 * CAS（consumed 0→1）防重复确认。存储不序列化整个 ToolUseBlock
 * （final 类 + Jackson 多态风险），只存 {id, name, input} 字段 JSON，
 * 恢复时用 {@code new ToolUseBlock(id, name, input)} 公共构造器重建实例（SPIKE S1 已验证）。
 *
 * <p>TTL：默认 30min（读时懒判断 + 定时清理兜底）。
 */
@Service
public class ConfirmContextStore {
    private static final Logger log = LoggerFactory.getLogger(ConfirmContextStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> TOOL_CALLS_TYPE =
        new TypeReference<>() {};

    private final DataSource dataSource;
    private final Duration ttl;

    public ConfirmContextStore(DataSource dataSource) {
        this(dataSource, Duration.ofMinutes(30));
    }

    public ConfirmContextStore(DataSource dataSource, Duration ttl) {
        this.dataSource = dataSource;
        this.ttl = ttl;
        initSchema();
    }

    /** 建表（幂等），失败 fail-fast */
    private void initSchema() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS confirm_context (
                  session_id      VARCHAR(255) PRIMARY KEY,
                  tool_calls_json MEDIUMTEXT NOT NULL,
                  reply_id        VARCHAR(64),
                  runtime_session_id VARCHAR(255),
                  runtime_user_id    VARCHAR(255),
                  created_at      DATETIME(3) NOT NULL,
                  consumed        TINYINT(1) NOT NULL DEFAULT 0,
                  KEY idx_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            log.info("ConfirmContextStore: confirm_context table ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init confirm_context table: " + e.getMessage(), e);
        }
    }

    /** 覆盖式写入确认上下文（同 session 新 ASK 覆盖旧条目；consumed 重置为 0） */
    public void put(String sessionId, List<Map<String, Object>> toolCalls,
                    String replyId, String runtimeSessionId, String runtimeUserId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 INSERT INTO confirm_context
                   (session_id, tool_calls_json, reply_id, runtime_session_id, runtime_user_id, created_at, consumed)
                 VALUES (?, ?, ?, ?, ?, NOW(3), 0)
                 ON DUPLICATE KEY UPDATE
                   tool_calls_json = VALUES(tool_calls_json),
                   reply_id = VALUES(reply_id),
                   runtime_session_id = VALUES(runtime_session_id),
                   runtime_user_id = VALUES(runtime_user_id),
                   created_at = NOW(3),
                   consumed = 0
                 """)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, toJson(toolCalls));
            stmt.setString(3, replyId);
            stmt.setString(4, runtimeSessionId);
            stmt.setString(5, runtimeUserId);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.error("ConfirmContextStore: put failed for {}: {}", sessionId, e.getMessage());
            throw new IllegalStateException("failed to persist confirm context: " + e.getMessage(), e);
        }
    }

    /**
     * 预检确认可用性（confirm-stream 预检；DB 版语义与进程内缓存一致）：
     * 行不存在/TTL 过期 → 404；已消费 → 409。
     */
    public void checkAvailable(String sessionId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT created_at, consumed FROM confirm_context WHERE session_id = ?")) {
            stmt.setString(1, sessionId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                throw new ConfirmContextNotFoundException(sessionId);
            }
            if (createdAt(rs).toInstant().plus(ttl).isBefore(Instant.now())) {
                throw new ConfirmContextNotFoundException(sessionId);
            }
            if (rs.getInt("consumed") != 0) {
                throw new ConfirmAlreadyConsumedException(sessionId);
            }
        } catch (AgentRuntimeService.ConfirmContextNotFoundException
                 | AgentRuntimeService.ConfirmAlreadyConsumedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("ConfirmContextStore: checkAvailable failed for {}: {}", sessionId, e.getMessage());
            throw new ConfirmContextNotFoundException(sessionId);
        }
    }

    /**
     * CAS 消费确认上下文（防重复确认）：UPDATE consumed 0→1，affected=0 时
     * 区分 404（不存在/过期）与 409（已消费），语义与现有接口完全一致。
     */
    public StoredRow consume(String sessionId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 UPDATE confirm_context SET consumed = 1
                 WHERE session_id = ? AND consumed = 0
                 """)) {
            stmt.setString(1, sessionId);
            if (stmt.executeUpdate() != 1) {
                // 未消费到：区分 404 / 409
                checkAvailable(sessionId);   // 抛 404 或 409
                throw new ConfirmAlreadyConsumedException(sessionId);
            }
        } catch (AgentRuntimeService.ConfirmContextNotFoundException
                 | AgentRuntimeService.ConfirmAlreadyConsumedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("ConfirmContextStore: consume update failed for {}: {}", sessionId, e.getMessage());
            throw new ConfirmContextNotFoundException(sessionId);
        }

        // 消费成功后读取完整行返回
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT tool_calls_json, reply_id, runtime_session_id, runtime_user_id, created_at "
                     + "FROM confirm_context WHERE session_id = ?")) {
            stmt.setString(1, sessionId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                throw new ConfirmContextNotFoundException(sessionId);
            }
            return new StoredRow(
                toToolCalls(rs.getString("tool_calls_json")),
                rs.getString("reply_id"),
                createdAt(rs).toInstant(),
                rs.getString("runtime_session_id"),
                rs.getString("runtime_user_id"));
        } catch (AgentRuntimeService.ConfirmContextNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("ConfirmContextStore: consume read failed for {}: {}", sessionId, e.getMessage());
            throw new ConfirmContextNotFoundException(sessionId);
        }
    }

    /**
     * 查询待确认上下文（刷新重建用；与 history 相同的前缀兼容 SQL，
     * 兼容 raw sid / fullThreadId 格式差异，见 stateless 设计 R7）。
     */
    public Optional<PendingConfirm> findPending(String sessionId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 SELECT tool_calls_json, reply_id, created_at FROM confirm_context
                 WHERE (session_id = ? OR session_id LIKE CONCAT('%:', ?))
                   AND consumed = 0
                   AND created_at > DATE_SUB(NOW(3), INTERVAL ? SECOND)
                 ORDER BY created_at DESC LIMIT 1
                 """)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setInt(3, (int) ttl.toSeconds());
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new PendingConfirm(
                rs.getString("reply_id"),
                toToolCalls(rs.getString("tool_calls_json")),
                createdAt(rs).toInstant()));
        } catch (Exception e) {
            log.warn("ConfirmContextStore: findPending failed for {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 删除确认上下文（供清理/测试） */
    public void delete(String sessionId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM confirm_context WHERE session_id = ?")) {
            stmt.setString(1, sessionId);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("ConfirmContextStore: delete failed for {}: {}", sessionId, e.getMessage());
        }
    }

    /** 清理已过期（TTL 之外）的确认上下文 */
    public void deleteExpired() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "DELETE FROM confirm_context WHERE created_at < DATE_SUB(NOW(3), INTERVAL ? SECOND)")) {
            stmt.setInt(1, (int) ttl.toSeconds());
            var n = stmt.executeUpdate();
            if (n > 0) {
                log.info("ConfirmContextStore: cleaned {} expired confirm(s)", n);
            }
        } catch (Exception e) {
            log.warn("ConfirmContextStore: deleteExpired failed: {}", e.getMessage());
        }
    }

    private static Timestamp createdAt(java.sql.ResultSet rs) throws java.sql.SQLException {
        return rs.getTimestamp("created_at");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return (Map<String, Object>) v;
    }

    /** 反序列化 tool_calls_json → 重建 ToolUseBlock 实例（SPIKE S1：公共构造器可用） */
    private static List<ToolUseBlock> toToolCalls(String json) {
        try {
            var list = MAPPER.readValue(json, TOOL_CALLS_TYPE);
            var result = new ArrayList<ToolUseBlock>(list.size());
            for (var m : list) {
                var id = (String) m.get("id");
                var name = (String) m.get("name");
                var input = asMap(m.get("input"));
                // ToolExecutor.validateInput 用 toolCall.getContent()(String) 做 schema 校验——
                // 三参构造器 content=null 会导致恢复执行时校验失败（"argument content is null"）。
                // 与 SDK 流式工具调用的 content 格式一致：填充 input 的 JSON 字符串。
                var content = m.get("content");
                String contentStr = content instanceof String s
                    ? s : (input == null ? null : toContentJson(input));
                result.add(new ToolUseBlock(id, name, input, contentStr, null));
            }
            return result;
        } catch (Exception e) {
            log.warn("ConfirmContextStore: failed to deserialize tool_calls: {}", e.getMessage());
            return List.of();
        }
    }

    /** 把 input Map 序列化为 content 字符串（与 SDK 流式工具调用累积格式一致） */
    private static String toContentJson(Map<String, Object> input) {
        try {
            return MAPPER.writeValueAsString(input);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJson(List<Map<String, Object>> toolCalls) {
        try {
            return MAPPER.writeValueAsString(toolCalls);
        } catch (Exception e) {
            throw new IllegalArgumentException("tool_calls is not JSON-serializable: " + e.getMessage(), e);
        }
    }

    /** 一行确认上下文记录（consume 返回值） */
    public record StoredRow(
        List<ToolUseBlock> toolCalls,
        String replyId,
        Instant createdAt,
        String runtimeSessionId,
        String runtimeUserId
    ) {
    }

    /** 待确认上下文（刷新重建用，无内部 runtime 细节） */
    public record PendingConfirm(
        String replyId,
        List<ToolUseBlock> toolCalls,
        Instant createdAt
    ) {
        /** 序列化为前端 pendingConfirm.tools[] 词表 */
        public List<Map<String, Object>> toolsJson() {
            return toolCalls.stream().map(tc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tool_call_id", tc.getId());
                m.put("name", tc.getName());
                m.put("input", tc.getInput());
                return m;
            }).toList();
        }
    }
}