package io.agentmanager.framework.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionUserStore;

/**
 * 会话 API（O7 定稿：会话接口统一迁至 /threads，页面数据端点保留 /debug）。
 *
 * <p>无状态单次流架构下 Thread 列表/历史为只读重建视角：
 * <ul>
 *   <li>GET /threads?userId=xxx —— agent_state 表 session_id 去重，可按 userId 过滤</li>
 *   <li>GET /threads/{sessionId}/history —— state_data 尽力解析 + 附 pendingConfirm
 *       （confirm_context 未消费待确认，供刷新后重建确认卡片）</li>
 *   <li>GET /threads/{sessionId}/llm-calls —— LLM 调用记录</li>
 * </ul>
 */
@RestController
@RequestMapping("/threads")
public class ThreadController {

    private static final Logger log = LoggerFactory.getLogger(ThreadController.class);

    private final DataSource dataSource;
    private final LLMLogger llmLogger;
    private final ConfirmContextStore confirmContextStore;
    private final SessionUserStore sessionUserStore;
    private final SessionEventStore sessionEventStore;

    public ThreadController(DataSource dataSource,
                            LLMLogger llmLogger,
                            ConfirmContextStore confirmContextStore,
                            SessionUserStore sessionUserStore,
                            SessionEventStore sessionEventStore) {
        this.dataSource = dataSource;
        this.llmLogger = llmLogger;
        this.confirmContextStore = confirmContextStore;
        this.sessionUserStore = sessionUserStore;
        this.sessionEventStore = sessionEventStore;
    }

    /** Thread 列表：agent_state 表 session_id 去重（真实会话来源）；可按 userId 过滤 */
    @GetMapping
    public List<Map<String, Object>> listThreads(
            @RequestParam(value = "userId", required = false) String userId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        // 确保 remark 列存在（listThreads 的 SQL 引用了 su.remark）
        ensureRemarkColumn();
        // 网关 Header 优先 > 请求参数
        if (headerUserId != null && !headerUserId.isBlank()) {
            userId = headerUserId;
        }
        var result = new ArrayList<Map<String, Object>>();
        try (var conn = dataSource.getConnection()) {
            // 如果指定了 userId，优先从 session_user 表过滤
            if (userId != null && !userId.isBlank()) {
                // LEFT JOIN 使用 LIKE 匹配：agent_state.session_id 格式为
                // slotId(userId, canonicalKey) = "{normalizeUser(userId)}:{canonicalKey}"，
                // 而 session_user.session_id = 前端 peerId。两者不一致，需用 LIKE 前缀匹配。
                try (var ps = conn.prepareStatement(
                        "SELECT su.session_id, su.remark, MAX(a.updated_at) AS updated_at "
                            + "FROM session_user su LEFT JOIN agent_state a "
                            + "ON a.session_id = su.session_id OR a.session_id LIKE CONCAT(su.session_id, ':%') "
                            + "WHERE su.user_id = ? "
                            + "GROUP BY su.session_id, su.remark "
                            + "ORDER BY COALESCE(MAX(a.updated_at), su.created_at) DESC")) {
                    ps.setString(1, userId);
                    var rs = ps.executeQuery();
                    while (rs.next()) {
                        var sid = rs.getString("session_id");
                        var remark = rs.getString("remark");
                        var updatedAt = rs.getTimestamp("updated_at");
                        var m = new LinkedHashMap<String, Object>();
                        m.put("session_id", sid);
                        m.put("thread_id", extractThreadId(sid));
                        m.put("user_id", userId);
                        m.put("title", remark != null ? remark : "");
                        m.put("updated_at", updatedAt != null ? updatedAt.toString() : "");
                        result.add(m);
                    }
                }
            } else {
                // 无 userId 过滤：返回全部会话，附带 user_id + title（从 session_user 表查）
                // LEFT JOIN 使用 LIKE 匹配（同上，agent_state.session_id 格式与 session_user 不一致）
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery(
                         "SELECT su.session_id, su.user_id, su.remark, MAX(a.updated_at) AS updated_at "
                             + "FROM session_user su LEFT JOIN agent_state a "
                             + "ON a.session_id = su.session_id OR a.session_id LIKE CONCAT(su.session_id, ':%') "
                             + "GROUP BY su.session_id, su.user_id, su.remark "
                             + "ORDER BY COALESCE(MAX(a.updated_at), su.created_at) DESC")) {
                    while (rs.next()) {
                        var sid = rs.getString("session_id");
                        var remark = rs.getString("remark");
                        var updatedAt = rs.getTimestamp("updated_at");
                        var m = new LinkedHashMap<String, Object>();
                        m.put("session_id", sid);
                        m.put("thread_id", extractThreadId(sid));
                        m.put("user_id", rs.getString("user_id"));
                        m.put("title", remark != null ? remark : "");
                        m.put("updated_at", updatedAt != null ? updatedAt.toString() : "");
                        result.add(m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("List threads failed: {}", e.getMessage());
        }
        return result;
    }

    /** Thread 历史消息：尽力从 agent_state.state_data 解析；附未消费 pendingConfirm 供刷新重建 */
    @GetMapping("/{sessionId}/history")
    public Map<String, Object> threadHistory(@PathVariable String sessionId) {
        var result = new LinkedHashMap<String, Object>();
        result.put("session_id", sessionId);
        result.put("pendingConfirm", pendingConfirmPayload(sessionId));
        // 产出文件卡片（present_file/create_oaf_zip 登记时 session_id = gw-hash）：
        // 历史回放与 SSE file_ready 渲染保持一致
        result.put("files", generatedFiles(sessionId));
        result.put("messages", loadMessages(sessionId));
        return result;
    }

    /** Thread 详情：返回会话元信息 + 历史消息 + pendingConfirm + 产出文件 */
    @GetMapping("/{sessionId}")
    public Map<String, Object> getThread(@PathVariable String sessionId) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        var result = new LinkedHashMap<String, Object>();
        result.put("session_id", sessionId);
        result.put("user_id", sessionUserStore.findUserIdBySession(sessionId));

        // 元信息：从 agent_state 取最新 updated_at（使用 LIKE 匹配前缀，与 loadMessages 一致）
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT MAX(updated_at) AS updated_at FROM agent_state "
                     + "WHERE session_id = ? OR session_id LIKE CONCAT(?, ':%')")) {
            ps.setString(1, sessionId);
            ps.setString(2, sessionId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                result.put("updated_at", rs.getTimestamp("updated_at") != null
                    ? rs.getTimestamp("updated_at").toString() : "");
            } else {
                result.put("updated_at", "");
            }
        } catch (Exception e) {
            log.warn("getThread meta failed for {}: {}", sessionId, e.getMessage());
            result.put("updated_at", "");
        }

        // 消息历史
        result.put("pendingConfirm", pendingConfirmPayload(sessionId));
        result.put("files", generatedFiles(sessionId));
        result.put("messages", loadMessages(sessionId));
        return result;
    }

    /** 删除会话：级联清理所有关联数据 */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteThread(@PathVariable String sessionId) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        int totalDeleted = 0;

        // 1. agent_state + agent_fs（会话状态与工作区文件）
        totalDeleted += deleteBySessionId("agent_state", sessionId);
        totalDeleted += deleteBySessionId("agent_fs", sessionId);

        // 2. session_event（SSE 事件历史）
        totalDeleted += deleteBySessionId("session_event", sessionId);

        // 3. session_user（会话-用户映射）
        totalDeleted += deleteBySessionId("session_user", sessionId);

        // 4. confirm_context（待确认上下文）
        confirmContextStore.delete(sessionId);
        totalDeleted++; // 近似计数

        // 5. turn_lease（活跃租约）
        totalDeleted += deleteBySessionId("turn_lease", sessionId);

        // 6. file_asset（产出文件：按 session_id 精确过滤）
        totalDeleted += deleteGeneratedFiles(sessionId);

        log.info("Thread deleted: {} (total rows affected: {})", sessionId, totalDeleted);
        return ResponseEntity.ok(Map.of(
            "session_id", sessionId,
            "deleted", true,
            "rows_affected", totalDeleted
        ));
    }

    /** 更新会话：目前仅支持重命名（title 字段，存入 session_user.remark） */
    @PatchMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> patchThread(
            @PathVariable String sessionId,
            @RequestBody PatchRequest body) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);

        if (body.title() != null && !body.title().isBlank()) {
            // 将 title 写入 session_user 表的 remark 字段
            upsertRemark(sessionId, body.title());
        }

        return ResponseEntity.ok(Map.of(
            "session_id", sessionId,
            "title", body.title() != null ? body.title() : ""
        ));
    }

    /** PATCH 请求体 */
    public record PatchRequest(String title) {}

    /** LLM 调用记录（LLMLogger） */
    @GetMapping("/{sessionId}/llm-calls")
    public Map<String, Object> llmCalls(@PathVariable String sessionId) {
        var calls = llmLogger.getCalls(sessionId).stream().map(c -> Map.<String, Object>of(
            "call_id", c.callId(),
            "timestamp", c.timestamp(),
            "request", c.request(),
            "response", c.response()
        )).toList();
        return Map.of("session_id", sessionId, "calls", calls);
    }

    /** 未消费待确认上下文 → 前端 pendingConfirm 词表；无则 null */
    private Map<String, Object> pendingConfirmPayload(String sessionId) {
        return confirmContextStore.findPending(sessionId)
            .map(p -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("reply_id", p.replyId());
                m.put("tools", p.toolsJson());
                m.put("created_at", p.createdAt() != null ? p.createdAt().toString() : "");
                return m;
            })
            .orElse(null);
    }

    /** session_id 格式: "{slug}__{threadId}"，取最后一个 "__" 后的部分作为展示 id */
    private String extractThreadId(String sessionId) {
        var idx = sessionId.lastIndexOf("__");
        if (idx >= 0 && idx < sessionId.length() - 2) {
            return sessionId.substring(idx + 2);
        }
        return sessionId;
    }

    /**
     * 会话产出文件（origin=generated）：present_file/create_oaf_zip 登记时
     * session_id = ctx.getSessionId()（即前端 peer / sessionId），按此精确过滤。
     * 同时兼容旧数据（session_id 为 null 时按 user_key 回退）。
     */
    private List<Map<String, Object>> generatedFiles(String sessionId) {
        var files = new ArrayList<Map<String, Object>>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT id, file_name, mime_type, size, reply_id FROM file_asset "
                     + "WHERE session_id = ? AND origin = 'generated' ORDER BY created_at")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("file_id", rs.getString("id"));
                    m.put("file_name", rs.getString("file_name"));
                    m.put("mime_type", rs.getString("mime_type"));
                    m.put("size", rs.getLong("size"));
                    var replyId = rs.getString("reply_id");
                    if (replyId != null && !replyId.isBlank()) {
                        m.put("reply_id", replyId);
                    }
                    files.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("generated files lookup failed for {}: {}", sessionId, e.getMessage());
        }
        return files;
    }

    // ===== 删除会话辅助方法 =====

    /** 按 session_id 删除指定表中的记录（防 SQL 注入：表名白名单校验）。
     *  agent_state / agent_fs 表的 session_id 格式为 slotId(userId, canonicalKey)，
     *  即 "{peerId}:{canonicalKey}"，与 session_user.session_id（前端 peerId）不一致，
     *  因此对这两张表额外使用 LIKE 前缀匹配删除变体记录。 */
    private int deleteBySessionId(String table, String sessionId) {
        var allowed = java.util.Set.of("agent_state", "agent_fs", "session_event",
            "session_user", "turn_lease");
        if (!allowed.contains(table)) {
            throw new IllegalArgumentException("Table not in delete whitelist: " + table);
        }
        var needsLike = java.util.Set.of("agent_state", "agent_fs");
        var sql = needsLike.contains(table)
            ? "DELETE FROM " + table + " WHERE session_id = ? OR session_id LIKE CONCAT(?, ':%')"
            : "DELETE FROM " + table + " WHERE session_id = ?";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            if (needsLike.contains(table)) {
                ps.setString(2, sessionId);
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("deleteBySessionId failed for table={}, sid={}: {}", table, sessionId, e.getMessage());
            return 0;
        }
    }

    /** 删除会话产出的 generated 文件（DB 行 + 存储对象） */
    private int deleteGeneratedFiles(String sessionId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT id, storage_key FROM file_asset WHERE session_id = ? AND origin = 'generated'")) {
            ps.setString(1, sessionId);
            var rs = ps.executeQuery();
            var toDelete = new ArrayList<String[]>();
            while (rs.next()) {
                toDelete.add(new String[]{rs.getString("id"), rs.getString("storage_key")});
            }
            for (var entry : toDelete) {
                try (var del = conn.prepareStatement("DELETE FROM file_asset WHERE id = ?")) {
                    del.setString(1, entry[0]);
                    del.executeUpdate();
                }
                count++;
                // 存储对象删除：失败仅告警，不影响整体结果
                if (entry[1] != null && !entry[1].isBlank()) {
                    try {
                        // 需要注入 FileStorage，暂不删除存储对象，留给定时清理兜底
                    } catch (Exception e) {
                        log.warn("storage object delete failed for {}: {}", entry[1], e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("deleteGeneratedFiles failed for {}: {}", sessionId, e.getMessage());
        }
        return count;
    }

    /** 从 agent_state 加载历史消息。
     *  AgentScope SDK 内部 session_id 格式为 slotId(userId, canonicalKey)，
     *  即 "{normalizeUser(userId)}:{canonicalKey}"（如 "debug-user_s1:chatui|x:agentId=main"），
     *  与 session_user.session_id（前端 peerId）不一致。
     *  因此查询使用 LIKE 匹配前缀 "peerId:%" 以覆盖 SDK 内部的 session_id 格式，
     *  同时保留精确匹配和后缀匹配以兼容旧格式。
     *  state_key 过滤 "agent_state"：确保只取消息状态，避免取到 sandbox_state 等无消息数据的记录。
     */
    private List<Map<String, Object>> loadMessages(String sessionId) {
        try (var conn = dataSource.getConnection();
             // 优先匹配精确 session_id，再匹配带分隔符前缀/后缀的变体
             var stmt = conn.prepareStatement(
                 "SELECT state_data FROM agent_state "
                     + "WHERE (session_id = ? "
                     +   "OR session_id LIKE CONCAT(?, ':%') "
                     +   "OR session_id LIKE CONCAT(?, '__%') "
                     +   "OR session_id LIKE CONCAT('%:', ?) "
                     +   "OR session_id LIKE CONCAT('%__', ?)) "
                     + "AND state_key = 'agent_state' "
                     + "ORDER BY item_index")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setString(3, sessionId);
            stmt.setString(4, sessionId);
            stmt.setString(5, sessionId);
            var rs = stmt.executeQuery();
            // agent_state 存储方式：单条 state（item_index=0 包含完整 AgentState JSON）
            // 或列表（每条消息一个 item_index）。两种方式都需要拼合后交给 StateDataParser。
            var fragments = new ArrayList<String>();
            while (rs.next()) {
                fragments.add(rs.getString("state_data"));
            }
            if (fragments.isEmpty()) {
                return List.of();
            }
            // 如果只有一条记录，直接解析；如果是多条消息，先合成为 JSON 数组
            var stateData = fragments.size() == 1
                ? fragments.get(0)
                : "[" + String.join(",", fragments) + "]";
            var msgs = io.agentmanager.framework.service.StateDataParser
                .toRoleContentList(io.agentmanager.framework.service.StateDataParser
                    .findMessagesArray(stateData));

            // 回填 reply_id：从 session_event 查询 AGENT_START 事件的 reply_id，
            // 按时间序分配给 assistant 消息
            var assistantCount = msgs.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .count();
            if (assistantCount == 0) {
                return msgs;
            }
            var replyIds = new ArrayList<String>();
            try (var ps = conn.prepareStatement(
                    "SELECT reply_id FROM session_event "
                        + "WHERE session_id = ? AND event_type = 'AGENT_START' "
                        + "AND reply_id IS NOT NULL AND reply_id != '' "
                        + "GROUP BY reply_id ORDER BY MIN(seq) ASC")) {
                ps.setString(1, sessionId);
                var rs2 = ps.executeQuery();
                while (rs2.next()) {
                    replyIds.add(rs2.getString("reply_id"));
                }
            } catch (Exception e) {
                log.debug("reply_id lookup skipped for {}: {}", sessionId, e.getMessage());
            }
            // 按 AGENT_START 出现顺序，依次分配 reply_id 给 assistant 消息
            int idx = 0;
            for (var m : msgs) {
                if ("assistant".equals(m.get("role")) && idx < replyIds.size()) {
                    m.put("reply_id", replyIds.get(idx));
                    idx++;
                }
            }
            return msgs;
        } catch (Exception e) {
            log.warn("loadMessages failed for {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 将 title 写入 session_user.remark（字段不存在则 ALTER TABLE 添加） */
    private void upsertRemark(String sessionId, String title) {
        ensureRemarkColumn();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                 INSERT INTO session_user (session_id, user_id, remark, created_at, updated_at)
                 VALUES (?, COALESCE((SELECT user_id FROM session_user WHERE session_id = ? LIMIT 1), 'unknown'), ?, NOW(3), NOW(3))
                 ON DUPLICATE KEY UPDATE remark = VALUES(remark), updated_at = NOW(3)
                 """)) {
            ps.setString(1, sessionId);
            ps.setString(2, sessionId);
            ps.setString(3, title);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("upsertRemark failed for {}: {}", sessionId, e.getMessage());
        }
    }

    /** 幂等确保 remark 列存在 */
    private void ensureRemarkColumn() {
        try (var conn = dataSource.getConnection();
             var rs = conn.getMetaData().getColumns(null, null, "session_user", "remark")) {
            if (!rs.next()) {
                try (var stmt = conn.createStatement()) {
                    stmt.executeUpdate("ALTER TABLE session_user ADD COLUMN remark VARCHAR(512) DEFAULT '' AFTER user_id");
                    log.info("Added 'remark' column to session_user table");
                }
            }
        } catch (Exception e) {
            log.debug("ensureRemarkColumn check skipped: {}", e.getMessage());
        }
    }
}
