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
 * <p>多行形态（V7 迁移，travel-fulfillment 设计 §5.1）：主键 (session_id, confirm_key)。
 * 本地 HITL 固定 {@link #LOCAL_KEY}（同 session 覆盖语义零回归）；远程确认桥落
 * {@code task:{task_id}} 行（同 session FIFO 排队，覆盖互踩消除）。
 * 远程行携带 remote_task 锚点 JSON（{service, task_id, tool_calls, child_reply_id}），
 * 消费路由凭该列判"不走父 state 恢复、改经 Bridge 调 /resume"。
 *
 * <p>TTL 分档：本地行默认 30min；远程行独立 TTL（{@code AGENT_REMOTE_CONFIRM_TTL_HOURS}，
 * 默认 24h——远程挂起不在父 state，AgentState 兜底不覆盖，必须独立判定）。
 * 均为读时懒判断 + 定时清理兜底。
 */
@Service
public class ConfirmContextStore {
    private static final Logger log = LoggerFactory.getLogger(ConfirmContextStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> TOOL_CALLS_TYPE =
        new TypeReference<>() {};

    /** 本地 HITL 行的固定 confirm_key（现状语义：同 session 覆盖写） */
    public static final String LOCAL_KEY = "local";

    /** 远程行默认独立 TTL：24h（AGENT_REMOTE_CONFIRM_TTL_HOURS 未配置时的回退值） */
    static final Duration DEFAULT_REMOTE_TTL = Duration.ofHours(24);

    private final DataSource dataSource;
    private final Duration ttl;
    private final Duration remoteTtl;

    public ConfirmContextStore(DataSource dataSource) {
        this(dataSource, Duration.ofMinutes(30));
    }

    public ConfirmContextStore(DataSource dataSource, Duration ttl) {
        this(dataSource, ttl, remoteTtlFromEnv());
    }

    /**
     * 远程行独立 TTL 读 env（{@code AGENT_REMOTE_CONFIRM_TTL_HOURS}，默认 24h）。
     * 仅在两参兼容构造（Spring 装配走此路径）里读取；测试用全参构造显式注入。
     * AgentManagerProperties 不承载该键（M1 范围外），故此处直读环境变量，解析失败回退默认。
     */
    static Duration remoteTtlFromEnv() {
        var raw = System.getenv("AGENT_REMOTE_CONFIRM_TTL_HOURS");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_REMOTE_TTL;
        }
        try {
            var hours = Double.parseDouble(raw.trim());
            return hours > 0 ? Duration.ofNanos((long) (hours * 3600.0 * 1_000_000_000.0)) : DEFAULT_REMOTE_TTL;
        } catch (NumberFormatException e) {
            return DEFAULT_REMOTE_TTL;
        }
    }

    /** 全参构造：本地 TTL 与远程行独立 TTL 分档（Bridge 超时治理与懒判断共用同一口径） */
    public ConfirmContextStore(DataSource dataSource, Duration ttl, Duration remoteTtl) {
        this.dataSource = dataSource;
        this.ttl = ttl;
        this.remoteTtl = remoteTtl != null ? remoteTtl : DEFAULT_REMOTE_TTL;
    }

    /** 本地行 TTL（历史语义：30min，AGENT_CLEANUP_CONFIRM_TTL_MINUTES） */
    public Duration ttl() {
        return ttl;
    }

    /** 远程行独立 TTL（设计 §5.1：默认 24h） */
    public Duration remoteTtl() {
        return remoteTtl;
    }

    /** 兼容重载：本地行写入（存量调用零改动，等价 confirm_key='local' 的覆盖写） */
    public void put(String sessionId, List<Map<String, Object>> toolCalls,
                    String replyId, String runtimeSessionId, String runtimeUserId) {
        put(LOCAL_KEY, sessionId, toolCalls, replyId, runtimeSessionId, runtimeUserId, null);
    }

    /**
     * 按 confirmKey 写入（多行形态）：同 (session_id, confirm_key) 覆盖写、consumed 重置 0；
     * 不同 confirm_key 各自成行（并发远程挂起 FIFO 排队，不互相覆盖）。
     *
     * @param confirmKey     'local' 或 'task:{task_id}'
     * @param remoteTaskJson 远程锚点 JSON（{service, task_id, tool_calls, child_reply_id}）；本地行 null
     */
    public void put(String confirmKey, String sessionId, List<Map<String, Object>> toolCalls,
                    String replyId, String runtimeSessionId, String runtimeUserId,
                    String remoteTaskJson) {
        var key = normalizedKey(confirmKey);
        log.debug("[HITL-DB] put: sessionId={}, confirmKey={}, replyId={}, toolCount={}, runtimeSid={}, runtimeUid={}",
            sessionId, key, replyId, toolCalls.size(), runtimeSessionId, runtimeUserId);
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 INSERT INTO confirm_context
                   (session_id, confirm_key, tool_calls_json, remote_task, reply_id,
                    runtime_session_id, runtime_user_id, created_at, consumed)
                 VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3), 0)
                 ON DUPLICATE KEY UPDATE
                   tool_calls_json = VALUES(tool_calls_json),
                   remote_task = VALUES(remote_task),
                   reply_id = VALUES(reply_id),
                   runtime_session_id = VALUES(runtime_session_id),
                   runtime_user_id = VALUES(runtime_user_id),
                   created_at = NOW(3),
                   consumed = 0
                 """)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            stmt.setString(3, toJson(toolCalls));
            stmt.setString(4, remoteTaskJson);
            stmt.setString(5, replyId);
            stmt.setString(6, runtimeSessionId);
            stmt.setString(7, runtimeUserId);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.error("ConfirmContextStore: put failed for {}: {}", sessionId, e.getMessage());
            throw new IllegalStateException("failed to persist confirm context: " + e.getMessage(), e);
        }
    }

    /**
     * 预检确认可用性（本地行，现状语义零回归；confirm-stream 预检；DB 版语义与进程内缓存一致）：
     * 行不存在/TTL 过期 → 404；已消费 → 409。
     */
    public void checkAvailable(String sessionId) {
        checkAvailable(sessionId, LOCAL_KEY);
    }

    /** 按 confirmKey 预检（远程确认路由预检用；TTL 按 local/remote 分档） */
    public void checkAvailable(String sessionId, String confirmKey) {
        var key = normalizedKey(confirmKey);
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT created_at, consumed FROM confirm_context "
                     + "WHERE session_id = ? AND confirm_key = ?")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                throw new ConfirmContextNotFoundException(sessionId);
            }
            if (createdAt(rs).toInstant().plus(ttlFor(key)).isBefore(Instant.now())) {
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
     * CAS 消费确认上下文（本地行，现状语义零回归；防重复确认）：UPDATE consumed 0→1，
     * affected=0 时区分 404（不存在/过期）与 409（已消费），语义与现有接口完全一致。
     */
    public StoredRow consume(String sessionId) {
        return consume(sessionId, LOCAL_KEY);
    }

    /**
     * 按 confirmKey CAS 消费（远程行路由用）：调用方应先经 pending 查询取得
     * 行的实际存储 session_id（兼容 raw sid 前缀差异）后以精确键消费。
     */
    public StoredRow consume(String sessionId, String confirmKey) {
        var key = normalizedKey(confirmKey);
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 UPDATE confirm_context SET consumed = 1
                 WHERE session_id = ? AND confirm_key = ? AND consumed = 0
                 """)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            if (stmt.executeUpdate() != 1) {
                // 未消费到：区分 404 / 409
                checkAvailable(sessionId, key);   // 抛 404 或 409
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
                 "SELECT tool_calls_json, remote_task, reply_id, runtime_session_id, runtime_user_id, created_at "
                     + "FROM confirm_context WHERE session_id = ? AND confirm_key = ?")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
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
     * 查询本地行待确认上下文（父 state 恢复路径专用，现状语义零回归）：
     * 仅 confirm_key='local'——远程行（'task:*'）不允许被本地恢复路径消费，
     * 否则会把远程任务误走父 state 恢复。FIFO 取最早未消费行。
     *
     * <p>与 history 相同的前缀兼容 SQL，兼容 raw sid / fullThreadId 格式差异
     * （见 stateless 设计 R7）。
     */
    public Optional<PendingConfirm> findPending(String sessionId) {
        return findPending(sessionId, LOCAL_KEY);
    }

    /** 按 confirmKey 查询待确认行（FIFO 最早未消费；TTL 按 local/remote 分档） */
    public Optional<PendingConfirm> findPending(String sessionId, String confirmKey) {
        var key = normalizedKey(confirmKey);
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 SELECT session_id, confirm_key, tool_calls_json, remote_task, reply_id,
                        runtime_session_id, runtime_user_id, created_at
                 FROM confirm_context
                 WHERE (session_id = ?
                        OR RIGHT(session_id, CHAR_LENGTH(?) + 1) = CONCAT(':', ?)
                        OR RIGHT(session_id, CHAR_LENGTH(?) + 2) = CONCAT('__', ?))
                   AND confirm_key = ?
                   AND consumed = 0
                   AND created_at > DATE_SUB(NOW(3), INTERVAL ? SECOND)
                 ORDER BY created_at ASC
                 LIMIT 1
                 """)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setString(3, sessionId);
            stmt.setString(4, sessionId);
            stmt.setString(5, sessionId);
            stmt.setString(6, key);
            stmt.setInt(7, (int) ttlFor(key).toSeconds());
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(pendingRowOf(rs));
        } catch (Exception e) {
            log.warn("ConfirmContextStore: findPending failed for {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 会话 FIFO 头：最早未消费行（任意 confirm_key，TTL 按 local/remote 分档懒过滤）。
     * history 的 pendingConfirm 取本方法（设计 §5.2：单卡 FIFO，前端零改动，
     * 并发远程挂起排队等待而非悬挂）。
     */
    public Optional<PendingConfirm> findHeadPending(String sessionId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 SELECT session_id, confirm_key, tool_calls_json, remote_task, reply_id,
                        runtime_session_id, runtime_user_id, created_at
                 FROM confirm_context
                 WHERE (session_id = ?
                        OR RIGHT(session_id, CHAR_LENGTH(?) + 1) = CONCAT(':', ?)
                        OR RIGHT(session_id, CHAR_LENGTH(?) + 2) = CONCAT('__', ?))
                   AND consumed = 0
                   AND ((confirm_key = 'local'
                         AND created_at > DATE_SUB(NOW(3), INTERVAL ? SECOND))
                     OR (confirm_key <> 'local'
                         AND created_at > DATE_SUB(NOW(3), INTERVAL ? SECOND)))
                 ORDER BY created_at ASC
                 LIMIT 1
                 """)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setString(3, sessionId);
            stmt.setString(4, sessionId);
            stmt.setString(5, sessionId);
            stmt.setInt(6, (int) ttl.toSeconds());
            stmt.setInt(7, (int) remoteTtl.toSeconds());
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(pendingRowOf(rs));
        } catch (Exception e) {
            log.warn("ConfirmContextStore: findHeadPending failed for {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 清理「幽灵本地行」（travel-fulfillment F20）：PROPAGATE 把子 ask 转发进父流后，
     * 既有捕获路径会把它落成 confirm_key='local' 行，但走该行的确认不转发远程任务
     * （member 收不到 resume，子任务永久挂起）——Bridge 落远程行前按 child tool_call_id
     * 对撞消费。匹配依赖 tool_calls_json 的 [{"id":"..."}] 稳定序列化（本地捕获与
     * 远程锚点同键）。返回消费行数（审计用）；前缀兼容谓词与查询同口径。
     */
    public int consumeGhostLocalRows(String sessionId, java.util.Collection<String> toolCallIds) {
        if (toolCallIds == null || toolCallIds.isEmpty()) {
            return 0;
        }
        var likePredicates = new StringBuilder();
        for (int i = 0; i < toolCallIds.size(); i++) {
            likePredicates.append(i == 0 ? "" : " OR ").append("tool_calls_json LIKE ?");
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 UPDATE confirm_context SET consumed = 1
                 WHERE (session_id = ?
                        OR RIGHT(session_id, CHAR_LENGTH(?) + 1) = CONCAT(':', ?)
                        OR RIGHT(session_id, CHAR_LENGTH(?) + 2) = CONCAT('__', ?))
                   AND confirm_key = 'local'
                   AND consumed = 0
                   AND (%s)
                 """.formatted(likePredicates))) {
            int i = 1;
            for (int k = 0; k < 5; k++) {
                stmt.setString(i++, sessionId);
            }
            for (var id : toolCallIds) {
                stmt.setString(i++, "%\"id\":\"" + id + "\"%");
            }
            return stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("ConfirmContextStore: consumeGhostLocalRows failed for {}: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 会话全部未过期远程行（FIFO）。Bridge 并发防御（设计 §5.3）用：
     * 已有未消费远程行又收到新挂起 → ERROR 审计、新任务照常排队。
     */
    public List<PendingConfirm> findUnconsumedRemote(String sessionId) {
        var rows = new ArrayList<PendingConfirm>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 SELECT session_id, confirm_key, tool_calls_json, remote_task, reply_id,
                        runtime_session_id, runtime_user_id, created_at
                 FROM confirm_context
                 WHERE (session_id = ?
                        OR RIGHT(session_id, CHAR_LENGTH(?) + 1) = CONCAT(':', ?)
                        OR RIGHT(session_id, CHAR_LENGTH(?) + 2) = CONCAT('__', ?))
                   AND confirm_key <> 'local'
                   AND consumed = 0
                   AND created_at > DATE_SUB(NOW(3), INTERVAL ? SECOND)
                 ORDER BY created_at ASC
                 """)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setString(3, sessionId);
            stmt.setString(4, sessionId);
            stmt.setString(5, sessionId);
            stmt.setInt(6, (int) remoteTtl.toSeconds());
            var rs = stmt.executeQuery();
            while (rs.next()) {
                rows.add(pendingRowOf(rs));
            }
        } catch (Exception e) {
            log.warn("ConfirmContextStore: findUnconsumedRemote failed for {}: {}", sessionId, e.getMessage());
        }
        return rows;
    }

    /**
     * 全部超远程 TTL 未消费的远程行（Bridge 超时治理扫描源，设计 §5.4：
     * 自动 resume(DENY, reason=confirm_timeout) + 审计）。
     */
    public List<PendingConfirm> findExpiredRemoteRows() {
        var rows = new ArrayList<PendingConfirm>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 SELECT session_id, confirm_key, tool_calls_json, remote_task, reply_id,
                        runtime_session_id, runtime_user_id, created_at
                 FROM confirm_context
                 WHERE confirm_key <> 'local'
                   AND consumed = 0
                   AND created_at < DATE_SUB(NOW(3), INTERVAL ? SECOND)
                 ORDER BY created_at ASC
                 """)) {
            stmt.setInt(1, (int) remoteTtl.toSeconds());
            var rs = stmt.executeQuery();
            while (rs.next()) {
                rows.add(pendingRowOf(rs));
            }
        } catch (Exception e) {
            log.warn("ConfirmContextStore: findExpiredRemoteRows failed: {}", e.getMessage());
        }
        return rows;
    }

    /** 行读取 → PendingConfirm（含实际存储 session_id / confirm_key / remote_task 锚点） */
    private static PendingConfirm pendingRowOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PendingConfirm(
            rs.getString("session_id"),
            rs.getString("confirm_key"),
            rs.getString("reply_id"),
            toToolCalls(rs.getString("tool_calls_json")),
            createdAt(rs).toInstant(),
            rs.getString("runtime_session_id"),
            rs.getString("runtime_user_id"),
            fromRemoteTaskJson(rs.getString("remote_task")));
    }

    /** confirm_key 规范化：null/空白回落 'local'（兼容重载语义） */
    private static String normalizedKey(String confirmKey) {
        return confirmKey == null || confirmKey.isBlank() ? LOCAL_KEY : confirmKey.trim();
    }

    /** TTL 分档：本地行 30min、远程行独立 TTL（设计 §5.1） */
    private Duration ttlFor(String confirmKey) {
        return LOCAL_KEY.equals(normalizedKey(confirmKey)) ? ttl : remoteTtl;
    }

    /** 反序列化 remote_task 锚点 JSON → Map；空/损坏返回 null（fail-soft，本地行恒 null） */
    private static Map<String, Object> fromRemoteTaskJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("ConfirmContextStore: failed to parse remote_task: {}", e.getMessage());
            return null;
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

    /**
     * 清理已过期（TTL 之外）的确认上下文。TTL 按 confirm_key 分档（设计 §5.1）：
     * 本地行 30min；远程行独立 TTL（默认 24h，超时治理由 Bridge 先行 resume(DENY)，
     * 这里只兜底清理已消费/已治理的残留）。
     */
    public void deleteExpired() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 DELETE FROM confirm_context
                 WHERE (confirm_key = 'local'
                        AND created_at < DATE_SUB(NOW(3), INTERVAL ? SECOND))
                    OR (confirm_key <> 'local'
                        AND created_at < DATE_SUB(NOW(3), INTERVAL ? SECOND))
                 """)) {
            stmt.setInt(1, (int) ttl.toSeconds());
            stmt.setInt(2, (int) remoteTtl.toSeconds());
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

    /**
     * 待确认上下文行（FIFO 查询行形态；runtime 两列供 HITL 恢复取会话身份）。
     *
     * <p>多行形态（V7）：{@code sessionId} 为该行的实际存储键（调用方传 raw sid 经
     * 前缀兼容 SQL 命中 fullThreadId 行时，消费必须以此精确键 CAS，避免二次 UPDATE 落空）；
     * {@code confirmKey} = 'local' 或 'task:{task_id}'；{@code remoteTask} 为远程锚点
     * JSON（{service, task_id, tool_calls, child_reply_id}），本地行 null。
     */
    public record PendingConfirm(
        String sessionId,
        String confirmKey,
        String replyId,
        List<ToolUseBlock> toolCalls,
        Instant createdAt,
        String runtimeSessionId,
        String runtimeUserId,
        Map<String, Object> remoteTask
    ) {
        /** 兼容构造（存量调用/测试：本地行形态） */
        public PendingConfirm(String replyId, List<ToolUseBlock> toolCalls, Instant createdAt,
                              String runtimeSessionId, String runtimeUserId) {
            this(null, LOCAL_KEY, replyId, toolCalls, createdAt, runtimeSessionId, runtimeUserId, null);
        }

        /** 是否远程确认行（消费路由判据：行含 remote_task 锚点即远程） */
        public boolean isRemote() {
            return remoteTask != null && !LOCAL_KEY.equals(confirmKey);
        }

        /** 远程锚点取值（service / task_id / child_reply_id），缺失返回 null */
        public String remoteTaskField(String field) {
            var v = remoteTask == null ? null : remoteTask.get(field);
            return v instanceof String s && !s.isBlank() ? s : null;
        }

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