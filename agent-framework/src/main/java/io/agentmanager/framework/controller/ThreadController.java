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

import io.agentmanager.framework.service.AgentStateReader;
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
    private final AgentStateReader agentStateReader;
    private final io.agentmanager.framework.service.ModelCatalog modelCatalog;
    private final int toolOutputMaxChars;

    /** 测试用简化构造：不注入 state 读取器配置（用默认截断上限） */
    public ThreadController(DataSource dataSource,
                            LLMLogger llmLogger,
                            ConfirmContextStore confirmContextStore,
                            SessionUserStore sessionUserStore,
                            SessionEventStore sessionEventStore,
                            io.agentmanager.framework.service.ModelCatalog modelCatalog) {
        this(dataSource, llmLogger, confirmContextStore, sessionUserStore, sessionEventStore,
            new AgentStateReader(dataSource), modelCatalog,
            io.agentmanager.framework.service.StateDataParser.DEFAULT_TOOL_OUTPUT_MAX_CHARS);
    }

    /** Spring 装配入口：截断上限取 AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS（默认 8000） */
    @org.springframework.beans.factory.annotation.Autowired
    public ThreadController(DataSource dataSource,
                            LLMLogger llmLogger,
                            ConfirmContextStore confirmContextStore,
                            SessionUserStore sessionUserStore,
                            SessionEventStore sessionEventStore,
                            AgentStateReader agentStateReader,
                            io.agentmanager.framework.service.ModelCatalog modelCatalog,
                            io.agentmanager.framework.config.HistoryConfig historyConfig) {
        this(dataSource, llmLogger, confirmContextStore, sessionUserStore, sessionEventStore,
            agentStateReader, modelCatalog,
            historyConfig != null ? historyConfig.toolOutputMaxChars()
                : io.agentmanager.framework.service.StateDataParser.DEFAULT_TOOL_OUTPUT_MAX_CHARS);
    }

    /** 全参构造（测试可直接指定截断上限） */
    public ThreadController(DataSource dataSource,
                            LLMLogger llmLogger,
                            ConfirmContextStore confirmContextStore,
                            SessionUserStore sessionUserStore,
                            SessionEventStore sessionEventStore,
                            AgentStateReader agentStateReader,
                            io.agentmanager.framework.service.ModelCatalog modelCatalog,
                            int toolOutputMaxChars) {
        this.dataSource = dataSource;
        this.llmLogger = llmLogger;
        this.confirmContextStore = confirmContextStore;
        this.sessionUserStore = sessionUserStore;
        this.sessionEventStore = sessionEventStore;
        this.agentStateReader = agentStateReader;
        this.modelCatalog = modelCatalog;
        this.toolOutputMaxChars = toolOutputMaxChars;
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
                        "SELECT su.session_id, su.remark, su.model, MAX(a.updated_at) AS updated_at "
                            + "FROM session_user su LEFT JOIN agent_state a "
                            + "ON a.session_id = su.session_id OR a.session_id LIKE CONCAT(su.session_id, ':%') "
                            + "WHERE su.user_id = ? "
                            + "GROUP BY su.session_id, su.remark, su.model "
                            + "ORDER BY COALESCE(MAX(a.updated_at), su.created_at) DESC")) {
                    ps.setString(1, userId);
                    var rs = ps.executeQuery();
                    while (rs.next()) {
                        var sid = rs.getString("session_id");
                        var remark = rs.getString("remark");
                        var model = rs.getString("model");
                        var updatedAt = rs.getTimestamp("updated_at");
                        var m = new LinkedHashMap<String, Object>();
                        m.put("session_id", sid);
                        m.put("thread_id", extractThreadId(sid));
                        m.put("user_id", userId);
                        m.put("title", remark != null ? remark : "");
                        m.put("model", model != null ? model : "");
                        m.put("updated_at", updatedAt != null ? updatedAt.toString() : "");
                        result.add(m);
                    }
                }
            } else {
                // 无 userId 过滤：返回全部会话，附带 user_id + title（从 session_user 表查）
                // LEFT JOIN 使用 LIKE 匹配（同上，agent_state.session_id 格式与 session_user 不一致）
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery(
                         "SELECT su.session_id, su.user_id, su.remark, su.model, MAX(a.updated_at) AS updated_at "
                             + "FROM session_user su LEFT JOIN agent_state a "
                             + "ON a.session_id = su.session_id OR a.session_id LIKE CONCAT(su.session_id, ':%') "
                             + "GROUP BY su.session_id, su.user_id, su.remark, su.model "
                             + "ORDER BY COALESCE(MAX(a.updated_at), su.created_at) DESC")) {
                    while (rs.next()) {
                        var sid = rs.getString("session_id");
                        var remark = rs.getString("remark");
                        var model = rs.getString("model");
                        var updatedAt = rs.getTimestamp("updated_at");
                        var m = new LinkedHashMap<String, Object>();
                        m.put("session_id", sid);
                        m.put("thread_id", extractThreadId(sid));
                        m.put("user_id", rs.getString("user_id"));
                        m.put("title", remark != null ? remark : "");
                        m.put("model", model != null ? model : "");
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

    /** Thread 历史消息：从 AgentState 解析（含工具状态与结果）；附待确认卡片与产出文件 */
    @GetMapping("/{sessionId}/history")
    public Map<String, Object> threadHistory(@PathVariable String sessionId) {
        // state 只读一次：消息解析与确认卡重建共用同一份快照（避免同请求内重复查库）
        var stateData = agentStateReader.loadStateData(sessionId);
        var result = new LinkedHashMap<String, Object>();
        result.put("session_id", sessionId);
        result.put("pendingConfirm", pendingConfirmPayload(sessionId, stateData));
        // 产出文件卡片（present_file/present_url 登记时 session_id = gw-hash）：
        // 历史回放与 SSE file_ready 渲染保持一致
        result.put("files", generatedFiles(sessionId));
        result.put("messages", loadMessages(sessionId, stateData));
        return result;
    }

    /** Thread 详情：返回会话元信息 + 历史消息 + pendingConfirm + 产出文件 */
    @GetMapping("/{sessionId}")
    public Map<String, Object> getThread(@PathVariable String sessionId) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        var result = new LinkedHashMap<String, Object>();
        result.put("session_id", sessionId);
        result.put("user_id", sessionUserStore.findUserIdBySession(sessionId));
        var sessionModel = sessionUserStore.findModelBySession(sessionId);
        result.put("model", sessionModel != null ? sessionModel : "");

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
        var stateData = agentStateReader.loadStateData(sessionId);
        result.put("pendingConfirm", pendingConfirmPayload(sessionId, stateData));
        result.put("files", generatedFiles(sessionId));
        result.put("messages", loadMessages(sessionId, stateData));
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

        // 2. session_event（SSE 事件历史）——已迁 Redis，走 store 删两个 key，
        //    不能再走 deleteBySessionId（那张表还在但已不再写入）
        var eventKeys = sessionEventStore.deleteSession(sessionId);
        if (eventKeys >= 0) {
            totalDeleted += eventKeys;
        }

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

    /**
     * 更新会话：title（重命名，存 session_user.remark）与 model（会话模型切换）均支持。
     * model 语义：字段缺省 = 不改变；""/system = 清除覆盖回默认模型；其他 = 校验后绑定。
     */
    @PatchMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> patchThread(
            @PathVariable String sessionId,
            @RequestBody PatchRequest body) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);

        if (body.title() != null && !body.title().isBlank()) {
            // 将 title 写入 session_user 表的 remark 字段
            upsertRemark(sessionId, body.title());
        }

        String appliedModel = null;
        if (body.model() != null) {
            var modelId = body.model().trim();
            if (!io.agentmanager.framework.service.ModelCatalog.isSystemSelection(modelId)) {
                var reject = modelCatalog != null ? modelCatalog.validateSelectable(modelId) : null;
                if (reject != null) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", reject,
                        "message", reject + ": " + modelId));
                }
            }
            appliedModel = io.agentmanager.framework.service.ModelCatalog.isSystemSelection(modelId)
                ? "" : modelId;
            sessionUserStore.upsertModel(sessionId, appliedModel);
        } else {
            appliedModel = sessionUserStore.findModelBySession(sessionId);
        }

        return ResponseEntity.ok(Map.of(
            "session_id", sessionId,
            "title", body.title() != null ? body.title() : "",
            "model", appliedModel != null ? appliedModel : ""
        ));
    }

    /** PATCH 请求体：title / model 均可选，至少传一个才有实际效果 */
    public record PatchRequest(String title, String model) {}

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

    /**
     * 待确认上下文 → 前端 pendingConfirm 词表；无则 null。
     *
     * <p><b>权威来源是 AgentState</b>：官方 SDK 把挂起的 ASKING 工具连同 replyId 一起
     * 持久化在最后一条 assistant 消息里（2.0.3 起），与会话同寿命——因此确认卡片在
     * confirm_context 的 30 分钟 TTL 之后依然能重建。
     * confirm_context 仅作兜底（老会话、SDK 未写入 metadata 的场景）。
     */
    private Map<String, Object> pendingConfirmPayload(String sessionId, String stateData) {
        var asking = stateData != null
            ? io.agentmanager.framework.service.StateDataParser.extractAskingToolCalls(
                io.agentmanager.framework.service.StateDataParser.findMessagesArray(stateData))
            : List.<Map<String, Object>>of();
        if (!asking.isEmpty()) {
            var m = new LinkedHashMap<String, Object>();
            // reply_id 取自 state 里最后一条 assistant 消息的 metadata（无则空串，恢复时由 SDK 兜底生成）
            m.put("reply_id", asking.get(0).getOrDefault("reply_id", ""));
            m.put("tools", asking);
            m.put("source", "agent_state");
            return m;
        }
        return confirmContextStore.findPending(sessionId)
            .map(p -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("reply_id", p.replyId());
                m.put("tools", p.toolsJson());
                m.put("created_at", p.createdAt() != null ? p.createdAt().toString() : "");
                m.put("source", "confirm_context");
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
     * 会话产出文件（origin=generated）：present_file/present_url 登记时
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
        // session_event 已不在白名单：它迁到 Redis 后由 sessionEventStore.deleteSession 处理，
        // 留着会让人以为还能从这张表删数据（表还在，但已不再写入）
        var allowed = java.util.Set.of("agent_state", "agent_fs",
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

    /** 从 agent_state 加载历史消息（状态 JSON 读取与解析口径见 {@link AgentStateReader}）。
     *
     *  <p>工具调用与结果的执行状态直接来自 AgentState（官方 SDK 自动持久化）：
     *  tool_use 块带 ToolCallState（pending/asking/allowed/submitted/finished），
     *  tool_result 块带 ToolResultState（success/error/denied/interrupted）与 output。
     *  DB 是消息级事实的权威来源，覆盖含 HITL 批准后的恢复段，不受 Redis 事件流 TTL 限制。
     *  <p>调用方传入已读好的 stateData（同一请求内消息解析与确认卡重建共用一份快照）。
     */
    private List<Map<String, Object>> loadMessages(String sessionId, String stateData) {
        try {
            if (stateData == null) {
                return List.of();
            }
            var msgs = io.agentmanager.framework.service.StateDataParser
                .toRoleContentList(io.agentmanager.framework.service.StateDataParser
                    .findMessagesArray(stateData), toolOutputMaxChars);

            // 回填 reply_id：取该 session 出现过的 reply_id，按**首个事件的 seq** 升序分配给
            // assistant 消息。数据源是 Redis 的 reply 索引（ZSET，score = 首个 seq），
            // 与原 SQL 的 GROUP BY reply_id ORDER BY MIN(seq) 等价。
            //
            // 与原查询有两处刻意的差异：
            //  - 原查询只认 event_type = 'AGENT_START'；索引覆盖带该 replyId 的**任意**事件。
            //    AGENT_START 是一个 turn 的首个事件，所以顺序一致；差别只在「AGENT_START 丢了
            //    的 turn」现在也会出现——那更正确，不是缺陷。
            //  - 失败时**返回 null 而不是空列表**：空列表会被下游当成「这个会话没有 reply」，
            //    而实际是「读不到」。两者对历史的呈现不同，不能在类型上混为一谈。
            var assistantCount = msgs.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .count();
            if (assistantCount == 0) {
                return msgs;
            }
            List<String> replyIds = null;
            try {
                replyIds = sessionEventStore.findReplyIds(sessionId);
            } catch (Exception e) {
                // 从 debug 提到 warn：静默缺 reply_id 会让前端的历史消息失去与 turn 的关联，
                // 这是**内容层面的错误**，不该按调试信息处理
                log.warn("reply_id lookup failed for {} — 历史消息将缺少 reply_id: {}",
                    sessionId, e.toString());
            }
            if (replyIds == null) {
                return msgs;
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
    /** 将 title 写入 session_user.remark（字段不存在则 ALTER TABLE 添加；SQL 统一在 SessionUserStore） */
    private void upsertRemark(String sessionId, String title) {
        ensureRemarkColumn();
        sessionUserStore.upsertRemark(sessionId, title);
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
