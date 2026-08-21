package io.agentmanager.framework.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.LLMLogger;

/**
 * 会话 API（O7 定稿：会话接口统一迁至 /threads，页面数据端点保留 /debug）。
 *
 * <p>无状态单次流架构下 Thread 列表/历史为只读重建视角：
 * <ul>
 *   <li>GET /threads —— agent_state 表 session_id 去重（真实会话来源）</li>
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

    public ThreadController(DataSource dataSource,
                            LLMLogger llmLogger,
                            ConfirmContextStore confirmContextStore) {
        this.dataSource = dataSource;
        this.llmLogger = llmLogger;
        this.confirmContextStore = confirmContextStore;
    }

    /** Thread 列表：agent_state 表 session_id 去重（真实会话来源） */
    @GetMapping
    public List<Map<String, Object>> listThreads() {
        var result = new ArrayList<Map<String, Object>>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                 "SELECT session_id, MAX(updated_at) AS updated_at FROM agent_state GROUP BY session_id ORDER BY updated_at DESC")) {
            while (rs.next()) {
                var sid = rs.getString("session_id");
                result.add(Map.of(
                    "session_id", sid,
                    "thread_id", extractThreadId(sid),
                    "updated_at", rs.getTimestamp("updated_at") != null
                        ? rs.getTimestamp("updated_at").toString() : ""
                ));
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
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT state_data FROM agent_state WHERE session_id = ? "
                     + "OR session_id LIKE CONCAT(?, ':%') "
                     + "OR session_id LIKE CONCAT('%:', ?) "
                     + "ORDER BY item_index DESC LIMIT 1")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setString(3, sessionId);
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

    /** session_id 格式: "{slug}:{threadId}"，取最后一个冒号后的部分作为展示 id */
    private String extractThreadId(String sessionId) {
        var idx = sessionId.lastIndexOf(':');
        if (idx >= 0 && idx < sessionId.length() - 1) {
            return sessionId.substring(idx + 1);
        }
        return sessionId;
    }
}
