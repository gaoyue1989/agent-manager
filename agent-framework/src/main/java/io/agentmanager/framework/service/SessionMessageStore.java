package io.agentmanager.framework.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * session_message 表（会话消息轨）：压缩后会话历史可查设计的消息级归档存储。
 * 设计文档：docs/session-history-archive-design.md。
 *
 * <p>与 agent_state（模型上下文轨，压缩后覆盖写、旧消息消失）分离的用户可见历史权威源：
 * append-only 语义——写入只新增/合并消息内容，从不因压缩删除行。
 * 写入由 {@link SessionMessageArchiveStateStore} 在每次 AgentState 落库时 write-through；
 * 查询由 GET /threads/{sid}/history 双源合并消费。
 *
 * <p>幂等：唯一键 (session_id, msg_id)；同消息多次落库按 {@link #mergeMsgJson} 合并
 * （文本取最长版本、状态取最新版本），重复归档/多副本重放零重复。
 */
@Service
public class SessionMessageStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMessageStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 普通消息（用户/助手/工具等 SDK 上下文消息） */
    public static final String KIND_MESSAGE = "message";
    /** 压缩摘要消息（SDK ConversationCompactor.buildSummaryMessage 产物，name 固定 __compaction_summary__） */
    public static final String KIND_COMPACTION_SUMMARY = "compaction_summary";
    /** SDK 压缩摘要消息名常量（与 ConversationCompactor 字节码一致） */
    public static final String SUMMARY_MESSAGE_NAME = "__compaction_summary__";

    /** 归档写入记录（写侧 DTO） */
    public record MessageRecord(String msgId, String kind, String role, String replyId, String msgJson) {}

    /** 归档行（读侧 DTO） */
    public record ArchivedRow(long id, String msgId, String kind, String role, String replyId,
                              String msgJson, Timestamp createdAt) {}

    private final DataSource dataSource;

    public SessionMessageStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 读-合并-写批量归档（幂等，fail-soft：失败仅告警不影响对话主链路）。
     *
     * <p>同 (session_id, msg_id) 已有行时按 {@link #mergeMsgJson} 合并后整体覆盖 msg_data；
     * reply_id 保持首个非空值（首轮盖戳不被后续轮次的 save 覆盖）。
     *
     * @param sessionKey slot 复合键（与 agent_state.session_id 同值，见归档装饰器 slotKey）
     */
    public void archiveBatch(String sessionKey, List<MessageRecord> records) {
        if (sessionKey == null || sessionKey.isBlank() || records == null || records.isEmpty()) {
            return;
        }
        try {
            // 1. 读旧值：一条 IN 查询取全批已存在的 msg_data（读-合并-写的「读」）
            Map<String, String> existing = new HashMap<>();
            var ids = new ArrayList<String>(records.size());
            for (var r : records) {
                if (r.msgId() != null && !r.msgId().isBlank() && r.msgJson() != null) {
                    ids.add(r.msgId());
                }
            }
            if (ids.isEmpty()) {
                return;
            }
            var placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            var selectSql = "SELECT msg_id, msg_data FROM session_message WHERE session_id = ? AND msg_id IN ("
                + placeholders + ")";
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, sessionKey);
                for (int i = 0; i < ids.size(); i++) {
                    ps.setString(2 + i, ids.get(i));
                }
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existing.put(rs.getString("msg_id"), rs.getString("msg_data"));
                    }
                }
            }

            // 2. 合并 + 3. 批量 upsert
            var upsertSql = """
                INSERT INTO session_message (session_id, msg_id, kind, role, reply_id, msg_data, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                ON DUPLICATE KEY UPDATE
                  kind = VALUES(kind),
                  role = VALUES(role),
                  reply_id = IF(reply_id IS NULL OR reply_id = '', VALUES(reply_id), reply_id),
                  msg_data = VALUES(msg_data),
                  updated_at = NOW(3)
                """;
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(upsertSql)) {
                int batch = 0;
                for (var r : records) {
                    if (r.msgId() == null || r.msgId().isBlank() || r.msgJson() == null) {
                        continue;
                    }
                    ps.setString(1, sessionKey);
                    ps.setString(2, r.msgId());
                    ps.setString(3, r.kind() != null ? r.kind() : KIND_MESSAGE);
                    ps.setString(4, r.role());
                    ps.setString(5, r.replyId());
                    ps.setString(6, mergeMsgJson(existing.get(r.msgId()), r.msgJson()));
                    ps.addBatch();
                    batch++;
                }
                if (batch > 0) {
                    ps.executeBatch();
                }
            }
        } catch (Exception e) {
            log.warn("SessionMessageStore: archiveBatch failed for {} ({} msgs): {}",
                sessionKey, records.size(), e.getMessage());
        }
    }

    /**
     * 查询会话归档消息：id 小于 beforeId（null = 最新页）中最新的至多 limit 条，按 id 升序返回。
     *
     * <p>session 匹配谓词与 {@link AgentStateReader#loadFragments} 同款 5 形 LIKE：
     * 归档行的 session_id 是 {@code {normalizeUser(userId)}:{sessionId}} 复合键，而调用方
     * 传入的可能是任一分量（peerId / threadId / gw-hash），须多形态兜底（勿收窄为 2 形）。
     *
     * @param limit 最大行数；{@code <= 0} 不限
     */
    public List<ArchivedRow> findPage(String sessionId, Long beforeId, int limit) {
        var result = new ArrayList<ArchivedRow>();
        if (sessionId == null || sessionId.isBlank()) {
            return result;
        }
        var sql = "SELECT id, msg_id, kind, role, reply_id, msg_data, created_at FROM session_message "
            + "WHERE (session_id = ? "
            +   "OR session_id LIKE CONCAT(?, ':%') "
            +   "OR session_id LIKE CONCAT(?, '__%') "
            +   "OR session_id LIKE CONCAT('%:', ?) "
            +   "OR session_id LIKE CONCAT('%__', ?)) "
            + (beforeId != null ? "AND id < ? " : "")
            + "ORDER BY id DESC"
            + (limit > 0 ? " LIMIT ?" : "");
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, sessionId);
            ps.setString(3, sessionId);
            ps.setString(4, sessionId);
            ps.setString(5, sessionId);
            int idx = 6;
            if (beforeId != null) {
                ps.setLong(idx++, beforeId);
            }
            if (limit > 0) {
                ps.setInt(idx, limit);
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ArchivedRow(rs.getLong("id"), rs.getString("msg_id"),
                        rs.getString("kind"), rs.getString("role"), rs.getString("reply_id"),
                        rs.getString("msg_data"), rs.getTimestamp("created_at")));
                }
            }
        } catch (Exception e) {
            log.warn("SessionMessageStore: findPage failed for {}: {}", sessionId, e.getMessage());
        }
        // DESC 取数后翻转为时间序（id 升序）
        java.util.Collections.reverse(result);
        return result;
    }

    /**
     * 全会话已归档 msg_id 集合：history 合并时判定 state 独有消息——凡已归档的 state 节点
     * 一律跳过（其归档行才是渲染源），避免分页首页把「归档行在其他页」的消息重复合入。
     * fail-soft：查询失败返回空集（等价回退为仅按当前页去重）。
     */
    public java.util.Set<String> findAllMsgIds(String sessionId) {
        var ids = new java.util.HashSet<String>();
        if (sessionId == null || sessionId.isBlank()) {
            return ids;
        }
        var sql = "SELECT DISTINCT msg_id FROM session_message "
            + "WHERE session_id = ? "
            +   "OR session_id LIKE CONCAT(?, ':%') "
            +   "OR session_id LIKE CONCAT(?, '__%') "
            +   "OR session_id LIKE CONCAT('%:', ?) "
            +   "OR session_id LIKE CONCAT('%__', ?)";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) {
                ps.setString(i, sessionId);
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("msg_id"));
                }
            }
        } catch (Exception e) {
            log.warn("SessionMessageStore: findAllMsgIds failed for {}: {}", sessionId, e.getMessage());
        }
        return ids;
    }

    /** 会话删除级联：按 5 形谓词删除该会话全部归档（fail-soft，返回删除行数） */
    public int deleteBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        var sql = "DELETE FROM session_message "
            + "WHERE session_id = ? "
            +   "OR session_id LIKE CONCAT(?, ':%') "
            +   "OR session_id LIKE CONCAT(?, '__%') "
            +   "OR session_id LIKE CONCAT('%:', ?) "
            +   "OR session_id LIKE CONCAT('%__', ?)";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) {
                ps.setString(i, sessionId);
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("SessionMessageStore: deleteBySession failed for {}: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /** 保留期清理：删除 updated_at 早于 cutoff 的归档（与 agent_state 7 天保留语义一致） */
    public int deleteBefore(Instant cutoff) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM session_message WHERE updated_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("SessionMessageStore: deleteBefore failed: {}", e.getMessage());
            return 0;
        }
    }

    // ===== 合并规则：文本取最长版本，状态取最新版本（设计文档 §6.4） =====

    /**
     * 合并同一条 Msg 的两版 JSON：
     * <ul>
     *   <li>新版本文本载荷不短（流式扩展、状态迁移）→ 整体取新，状态天然最新；</li>
     *   <li>新版本是被裁剪/截断的收缩版（compaction truncateArgs/pruneToolResults）→
     *       旧文本保底，状态类字段（块 state/metadata、顶层 metadata/usage/timestamp）取新；
     *       tool_use.content 为 input 的镜像回填，随新。</li>
     * </ul>
     * 解析失败按新版本落库（fail-soft，宁新勿丢）。
     */
    public static String mergeMsgJson(String oldJson, String newJson) {
        if (oldJson == null || oldJson.isBlank()) {
            return newJson;
        }
        if (newJson == null || newJson.isBlank()) {
            return oldJson;
        }
        try {
            var oldNode = MAPPER.readTree(oldJson);
            var newNode = MAPPER.readTree(newJson);
            if (!(oldNode instanceof ObjectNode oldObj) || !(newNode instanceof ObjectNode newObj)) {
                return newJson;
            }
            if (payloadLength(newObj) >= payloadLength(oldObj)) {
                return newJson;
            }
            var merged = oldObj.deepCopy();
            for (var field : new String[] {"metadata", "usage", "timestamp", "name", "role"}) {
                if (newObj.has(field) && !newObj.get(field).isNull()) {
                    merged.set(field, newObj.get(field));
                }
            }
            overlayContentStates(merged, newObj);
            return MAPPER.writeValueAsString(merged);
        } catch (Exception e) {
            return newJson;
        }
    }

    /** 文本载荷总长度（递归）：text/thinking 正文 + tool_use.input（与 content 镜像）+ tool_result.output（嵌套块数组） */
    private static long payloadLength(JsonNode msg) {
        var content = msg.path("content");
        if (content.isTextual()) {
            return content.asText().length();
        }
        return blockPayloadLength(content);
    }

    private static long blockPayloadLength(JsonNode blocks) {
        long len = 0;
        if (blocks == null || !blocks.isArray()) {
            return len;
        }
        for (var block : blocks) {
            if (!block.isObject()) {
                continue;
            }
            switch (block.path("type").asText("")) {
                case "text" -> len += block.path("text").asText("").length();
                case "thinking" -> len += block.path("thinking").asText("").length();
                case "tool_use" -> {
                    len += block.path("input").toString().length();
                    len += block.path("content").asText("").length();
                }
                // ToolResultBlock.getOutput() 是嵌套 ContentBlock 数组（output 文本在其中的 text 块）
                case "tool_result" -> len += blockPayloadLength(block.path("output"));
                default -> { }
            }
        }
        return len;
    }

    /**
     * 收缩版覆盖：块级按 id 对齐，仅取「状态类」字段（state/metadata；tool_use.content 为
     * 参数镜像随新），文本载荷字段（text/thinking/input/output）保持旧版——这正是保底目的。
     */
    private static void overlayContentStates(ObjectNode merged, ObjectNode newNode) {
        if (!(merged.get("content") instanceof com.fasterxml.jackson.databind.node.ArrayNode oldArr)
            || !(newNode.get("content") instanceof com.fasterxml.jackson.databind.node.ArrayNode newArr)) {
            return;
        }
        var newById = new HashMap<String, JsonNode>();
        for (var block : newArr) {
            var id = block.path("id").asText("");
            if (!id.isBlank()) {
                newById.put(id, block);
            }
        }
        for (int i = 0; i < oldArr.size(); i++) {
            if (!(oldArr.get(i) instanceof ObjectNode oldBlock)) {
                continue;
            }
            var id = oldBlock.path("id").asText("");
            if (id.isBlank() || !(newById.get(id) instanceof ObjectNode newBlock)) {
                continue;
            }
            for (var field : new String[] {"state", "metadata"}) {
                if (newBlock.has(field) && !newBlock.get(field).isNull()) {
                    oldBlock.set(field, newBlock.get(field));
                }
            }
            // tool_use.content 是 input 的 JSON 镜像（ASKING 回填产物），属状态类，随新
            if ("tool_use".equals(oldBlock.path("type").asText(""))
                && newBlock.has("content") && !newBlock.get("content").isNull()) {
                oldBlock.set("content", newBlock.get("content"));
            }
        }
    }
}
