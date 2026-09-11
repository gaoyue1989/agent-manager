package io.agentmanager.framework.controller;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentmanager.framework.config.AguiProperties;
import io.agentmanager.framework.service.AguiInterruptStore;
import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.StateDataParser;
import io.agentmanager.framework.service.TurnLeaseStore;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AG-UI CopilotKit 配套 REST（agui-migration-plan §5.2，数据源全部 MySQL 化）。
 *
 * <p>契约来源：PR #2554 后端路由（CopilotKitRouteConfiguration）反推；CopilotKit 客户端
 * 实际调用行为（是否调 POST /threads、archived 展示等）由 R7 spike 验证后回填定稿。
 * 旧会话（session_id 含 ":" 的 Channel/A2A 复合 key）标注只读（archived，D5）。
 */
@RestController
@RequestMapping("/agui/run")
public class AguiThreadsController {

    private static final Logger log = LoggerFactory.getLogger(AguiThreadsController.class);

    private final DataSource dataSource;
    private final AguiProperties props;
    private final AguiInterruptStore interruptStore;
    private final ConfirmContextStore confirmContextStore;
    private final TurnLeaseStore turnLeaseStore;
    private final io.agentmanager.framework.model.OafConfig oafConfig;

    public AguiThreadsController(DataSource dataSource,
                                 AguiProperties props,
                                 AguiInterruptStore interruptStore,
                                 ConfirmContextStore confirmContextStore,
                                 TurnLeaseStore turnLeaseStore,
                                 io.agentmanager.framework.model.OafConfig oafConfig) {
        this.dataSource = dataSource;
        this.props = props;
        this.interruptStore = interruptStore;
        this.confirmContextStore = confirmContextStore;
        this.turnLeaseStore = turnLeaseStore;
        this.oafConfig = oafConfig;
    }

    /**
     * agents 列表 + capabilities + transport（R7 spike 定稿）。
     * 响应结构按 CopilotKit 1.65 客户端实测契约：agents 为 <b>以 agentId 为键的对象 map</b>
     * （runtimeInfo.agents Object.entries 注册），非数组；capabilities 可选。
     * agent 标识/描述取 OAF 包元数据（镜像共用，避免业务 agent 错报为 release-agent）。
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        var key = oafConfig.agentKey();
        var agentId = key == null || key.isBlank() ? props.agentId() : key;
        var description = oafConfig.description();
        if (description == null || description.isBlank()) {
            description = "OAF Agent";
        }
        var capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("threads", true);
        capabilities.put("hitl", true);
        var agent = new LinkedHashMap<String, Object>();
        agent.put("description", description);
        agent.put("capabilities", capabilities);
        var agents = new LinkedHashMap<String, Object>();
        agents.put(agentId, agent);
        var result = new LinkedHashMap<String, Object>();
        result.put("agents", agents);
        result.put("version", "1.0.0");
        return result;
    }

    /**
     * threads 列表：agent_state GROUP BY session_id，含旧 gw-hash/复合 key 会话（只读标注 archived）。
     * 会话 key 语义（D5）：AG-UI 新会话 key=纯 threadId（无 ":"）；旧 Channel/A2A key 含 ":"。
     */
    @GetMapping("/threads")
    public Map<String, Object> listThreads(@RequestParam(required = false) Integer limit) {
        var threads = new ArrayList<Map<String, Object>>();
        int cap = limit != null && limit > 0 ? Math.min(limit, 100) : 50;
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                 "SELECT session_id, MAX(updated_at) AS updated_at FROM agent_state "
                     + "GROUP BY session_id ORDER BY updated_at DESC LIMIT " + cap)) {
            while (rs.next()) {
                var sid = rs.getString("session_id");
                var userId = userIdOf(sid);
                var threadIdPart = threadIdOf(sid);
                var thread = new LinkedHashMap<String, Object>();
                thread.put("id", threadIdPart);
                thread.put("updatedAt", rs.getTimestamp("updated_at") != null
                    ? rs.getTimestamp("updated_at").toInstant().toString() : null);
                // archived：AG-UI 新会话 threadId 为前端生成的 uuid；旧 Channel/A2A key
                // （gw-hash/用户命名）非 uuid → 只读标注（D5；展示行为随 R7 spike 定）
                thread.put("metadata", Map.of("userId", userId, "archived", !isUuid(threadIdPart)));
                threads.add(thread);
            }
        } catch (Exception e) {
            log.warn("agui threads list failed: {}", e.getMessage());
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("threads", threads);
        return result;
    }

    /** 创建 thread：落点未定（R7）——agent_state 由 SDK 写入，平台不插行；threadId 由前端首 run 隐式创建 */
    @PostMapping("/threads")
    public Map<String, Object> createThread() {
        return Map.of();
    }

    /**
     * 删除 thread：agent_state（by session_id）+ agent_fs（key/namespace 模糊匹配，uuid 精度足够）
     * + 同 thread 的 agui_interrupt / turn_lease 记录（对齐 SessionCleanupService 清理范围）。
     */
    @DeleteMapping("/threads/{threadId}")
    public Map<String, Object> deleteThread(@PathVariable String threadId) {
        int state = 0;
        int fs = 0;
        try (var conn = dataSource.getConnection()) {
            try (var stmt = conn.prepareStatement(
                "DELETE FROM agent_state WHERE session_id = ? OR session_id LIKE CONCAT('%:', ?)")) {
                stmt.setString(1, threadId);
                stmt.setString(2, threadId);
                state = stmt.executeUpdate();
            }
            try (var stmt = conn.prepareStatement(
                "DELETE FROM agent_fs WHERE item_key LIKE CONCAT('%', ?, '%') "
                    + "OR namespace_path LIKE CONCAT('%', ?, '%')")) {
                stmt.setString(1, threadId);
                stmt.setString(2, threadId);
                fs = stmt.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("agui thread delete failed (threadId={}): {}", threadId, e.getMessage());
            return Map.of("deleted", false, "detail", e.getMessage() != null ? e.getMessage() : "");
        }
        interruptStore.delete(threadId);
        deleteTurnLease(threadId);
        return Map.of("deleted", true, "agent_state", state, "agent_fs", fs);
    }

    /**
     * 切换线程恢复消息列表：agent_state → StateDataParser → AG-UI 消息数组；
     * 附挂起确认元数据——新链路 pendingInterrupts（agui_interrupt）、旧链路
     * pendingConfirm（confirm_context，过渡期共存，Phase 3 随 D8 退役）。
     */
    @GetMapping("/threads/{threadId}/messages")
    public Map<String, Object> threadMessages(@PathVariable String threadId) {
        var result = new LinkedHashMap<String, Object>();
        result.put("messages", readMessages(threadId));
        // 新链路挂起 interrupt（R11：前端刷新后据此重建确认卡片/手工 resume[]）
        try {
            var open = interruptStore.load(threadId);
            result.put("pendingInterrupts", open.isEmpty() ? List.of() : open);
        } catch (Exception e) {
            // 元数据损坏（R10）不影响消息列表读取，仅记空
            log.warn("pendingInterrupts load failed (threadId={}): {}", threadId, e.getMessage());
            result.put("pendingInterrupts", List.of());
        }
        // 旧链路挂起确认（旧会话只读展示用，D5/D8）
        try {
            result.put("pendingConfirm", confirmContextStore.findPending(threadId)
                .map(p -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("reply_id", p.replyId());
                    m.put("tools", p.toolsJson());
                    m.put("created_at", p.createdAt() != null ? p.createdAt().toString() : "");
                    return m;
                }).orElse(null));
        } catch (Exception e) {
            result.put("pendingConfirm", null);
        }
        return result;
    }

    /** 共享状态（AG-UI state，暂未使用） */
    @GetMapping("/threads/{threadId}/state")
    public Map<String, Object> threadState(@PathVariable String threadId) {
        return Map.of();
    }

    // ===== 内部 =====

    /** agent_state → StateDataParser → AG-UI 消息数组（{id, role, content}） */
    private List<Map<String, Object>> readMessages(String threadId) {
        var messages = new ArrayList<Map<String, Object>>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT state_data FROM agent_state WHERE session_id = ? "
                     + "OR session_id LIKE CONCAT('%:', ?) "
                     + "ORDER BY item_index DESC LIMIT 1")) {
            stmt.setString(1, threadId);
            stmt.setString(2, threadId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return messages;
            }
            var rows = StateDataParser.toRoleContentList(
                StateDataParser.findMessagesArray(rs.getString("state_data")));
            int idx = 0;
            for (var row : rows) {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", "msg-" + (idx++));
                m.put("role", row.get("role"));
                m.put("content", row.get("content"));
                messages.add(m);
            }
        } catch (Exception e) {
            log.warn("agui thread messages read failed (threadId={}): {}", threadId, e.getMessage());
        }
        return messages;
    }

    /** agent_state 复合 key 拆解：store key = {userId}:{sessionId}（SDK 内部约定，实测确认） */
    private static String userIdOf(String sessionId) {
        int idx = sessionId.indexOf(':');
        return idx > 0 ? sessionId.substring(0, idx) : "";
    }

    private static String threadIdOf(String sessionId) {
        int idx = sessionId.indexOf(':');
        return idx > 0 && idx < sessionId.length() - 1 ? sessionId.substring(idx + 1) : sessionId;
    }

    private static boolean isUuid(String value) {
        return value != null && value.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private void deleteTurnLease(String threadId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM turn_lease WHERE session_id = ?")) {
            stmt.setString(1, threadId);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("turn_lease delete failed (threadId={}): {}", threadId, e.getMessage());
        }
    }
}
