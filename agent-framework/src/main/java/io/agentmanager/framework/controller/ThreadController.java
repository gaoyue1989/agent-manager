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
                // 直接用 LEFT JOIN 查 session_user + agent_state，已覆盖"agent_state 已清理"的场景
                try (var ps = conn.prepareStatement(
                        "SELECT su.session_id, su.remark, MAX(a.updated_at) AS updated_at "
                            + "FROM session_user su LEFT JOIN agent_state a ON su.session_id = a.session_id "
                            + "WHERE su.user_id = ? "
                            + "GROUP BY su.session_id, su.remark ORDER BY updated_at DESC")) {
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
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery(
                         "SELECT su.session_id, su.user_id, su.remark, MAX(a.updated_at) AS updated_at "
                             + "FROM session_user su LEFT JOIN agent_state a ON su.session_id = a.session_id "
                             + "GROUP BY su.session_id, su.user_id, su.remark ORDER BY updated_at DESC")) {
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
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT state_data FROM agent_state WHERE session_id = ? "
                     + "OR session_id LIKE CONCAT(?, '__%') "
                     + "OR session_id LIKE CONCAT('%__', ?) "
                     + "OR session_id LIKE CONCAT(?, ':%') "
                     + "OR session_id LIKE CONCAT('%:', ?) "
                     + "ORDER BY item_index DESC LIMIT 1")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setString(3, sessionId);
            stmt.setString(4, sessionId);
            stmt.setString(5, sessionId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                result.put("messages", List.of());
                return result;
            }
            var stateData = rs.getString("state_data");
            result.put("messages", io.agentmanager.framework.service.StateDataParser
                .toRoleContentList(io.agentmanager.framework.service.StateDataParser
                    .findMessagesArray(stateData)));
            return result;
        } catch (Exception e) {
            log.warn("thread history read failed for {}: {}", sessionId, e.getMessage());
            result.put("messages", List.of());
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return result;
        }
    }

    /** Thread 详情：返回会话元信息 + 历史消息 + pendingConfirm + 产出文件 */
    @GetMapping("/{sessionId}")
    public Map<String, Object> getThread(@PathVariable String sessionId) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        var result = new LinkedHashMap<String, Object>();
        result.put("session_id", sessionId);
        result.put("user_id", sessionUserStore.findUserIdBySession(sessionId));

        // 元信息：从 agent_state 取最新 updated_at
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT MAX(updated_at) AS updated_at FROM agent_state WHERE session_id = ?")) {
            ps.setString(1, sessionId);
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

        // 6. file_asset（产出文件：按 peer 即 user_key 过滤）
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
     * user_key = RuntimeContext 的 userId（Channel 流程 = peer，每会话唯一）。
     * 注意不能用 session_id（gw-hash 对全部 ChatUiChannel 会话为常量，会跨会话串文件）。
     * 传入 sessionId 兼容 fullKey（"peer__gw-hash" 或旧格式 "peer:gw-hash"）与裸 peer 两种形态。
     */
    private List<Map<String, Object>> generatedFiles(String sessionId) {
        var peer = sessionId;
        // 兼容 "__"（新格式）和 ":"（旧格式/网关格式）
        var idx = sessionId.indexOf('_');
        if (idx < 0) {
            idx = sessionId.indexOf(':');
        }
        if (idx > 0) {
            peer = sessionId.substring(0, idx);
        }
        if (peer.isBlank()) {
            return List.of();
        }
        var files = new ArrayList<Map<String, Object>>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT id, file_name, mime_type, size FROM file_asset "
                     + "WHERE user_key = ? AND origin = 'generated' ORDER BY created_at")) {
            ps.setString(1, peer);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    files.add(Map.of(
                        "file_id", rs.getString("id"),
                        "file_name", rs.getString("file_name"),
                        "mime_type", rs.getString("mime_type"),
                        "size", rs.getLong("size")));
                }
            }
        } catch (Exception e) {
            log.warn("generated files lookup failed for {}: {}", sessionId, e.getMessage());
        }
        return files;
    }

    // ===== 删除会话辅助方法 =====

    /** 按 session_id 删除指定表中的记录（防 SQL 注入：表名白名单校验） */
    private int deleteBySessionId(String table, String sessionId) {
        var allowed = java.util.Set.of("agent_state", "agent_fs", "session_event",
            "session_user", "turn_lease");
        if (!allowed.contains(table)) {
            throw new IllegalArgumentException("Table not in delete whitelist: " + table);
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "DELETE FROM " + table + " WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("deleteBySessionId failed for table={}, sid={}: {}", table, sessionId, e.getMessage());
            return 0;
        }
    }

    /** 删除会话产出的 generated 文件（DB 行 + 存储对象） */
    private int deleteGeneratedFiles(String sessionId) {
        var peer = extractPeer(sessionId);
        if (peer.isBlank()) {
            return 0;
        }
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT id, storage_key FROM file_asset WHERE user_key = ? AND origin = 'generated'")) {
            ps.setString(1, peer);
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

    /** 从 sessionId 提取 peer（与 generatedFiles 逻辑一致） */
    private String extractPeer(String sessionId) {
        var peer = sessionId;
        var idx = sessionId.indexOf('_');
        if (idx < 0) {
            idx = sessionId.indexOf(':');
        }
        if (idx > 0) {
            peer = sessionId.substring(0, idx);
        }
        return peer.isBlank() ? "" : peer;
    }

    /** 从 agent_state 加载历史消息 */
    private List<Map<String, Object>> loadMessages(String sessionId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT state_data FROM agent_state WHERE session_id = ? "
                     + "OR session_id LIKE CONCAT(?, '__%') "
                     + "OR session_id LIKE CONCAT('%__', ?) "
                     + "OR session_id LIKE CONCAT(?, ':%') "
                     + "OR session_id LIKE CONCAT('%:', ?) "
                     + "ORDER BY item_index DESC LIMIT 1")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setString(3, sessionId);
            stmt.setString(4, sessionId);
            stmt.setString(5, sessionId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return List.of();
            }
            var stateData = rs.getString("state_data");
            return io.agentmanager.framework.service.StateDataParser
                .toRoleContentList(io.agentmanager.framework.service.StateDataParser
                    .findMessagesArray(stateData));
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
