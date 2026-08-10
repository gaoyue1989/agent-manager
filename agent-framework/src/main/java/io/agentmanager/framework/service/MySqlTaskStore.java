package io.agentmanager.framework.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;

/**
 * A2A TaskStore 的 MySQL 实现。
 *
 * <p>消息历史已由 AgentScope 内部自动持久化：
 * A2A / Channel 模式最终都走 HarnessAgent.streamEvents → ReActAgent.saveAgentState
 * → MysqlAgentStateStore.save() → agent_state 表（state_data 含 context[] 消息数组）。
 *
 * <p>因此本实现以 agent_state 为唯一事实来源：
 * <ul>
 *   <li>{@link #get(String)} 读取 agent_state 构造 A2A Task（含 history 消息列表）</li>
 *   <li>{@link #save(Task)} 为 no-op（消息已由 AgentScope 写入，避免双写与数据不一致）</li>
 *   <li>{@link #delete(String)} 暂不实现（与 agent_state 生命周期解耦）</li>
 * </ul>
 */
public class MySqlTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(MySqlTaskStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;

    public MySqlTaskStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(Task task) {
        // 消息历史已由 AgentScope ReActAgent.saveAgentState() 自动写入 agent_state，
        // MySqlTaskStore 以 agent_state 为唯一事实来源，save 无需重复落库。
        // 仅记录日志便于排查（artifacts/status 等 A2A 特有字段暂不持久化）。
        log.debug("TaskStore.save skipped (agent_state is source of truth): {}", task.getId());
    }

    @Override
    public Task get(String taskId) {
        // SELECT state_data + updated_at FROM agent_state WHERE session_id = ? ORDER BY item_index DESC LIMIT 1
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT state_data, updated_at FROM agent_state "
                     + "WHERE session_id = ? ORDER BY item_index DESC LIMIT 1")) {
            stmt.setString(1, taskId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return null;
            }
            var stateData = rs.getString("state_data");
            var updatedAt = rs.getTimestamp("updated_at");
            return buildTask(taskId, stateData, updatedAt);
        } catch (Exception e) {
            log.warn("TaskStore.get failed for {}: {}", taskId, e.getMessage());
            return null;
        }
    }

    @Override
    public void delete(String taskId) {
        // 与 agent_state 生命周期解耦（SessionCleanupService 负责清理），暂不实现
        log.debug("TaskStore.delete skipped: {}", taskId);
    }

    /** 从 state_data 构造 A2A Task 对象 */
    private Task buildTask(String taskId, String stateData, Timestamp updatedAt) {
        var messages = extractA2aMessages(stateData);
        var state = inferTaskState(stateData);
        // TaskStatus 公共构造仅 (state) 与 (state, message, timestamp)；
        // 用 (state) 构造，timestamp 由 SDK 回填（当前未用，保持简单）
        var status = new TaskStatus(state);
        return new Task(taskId, taskId, status, List.of(), messages, Map.of());
    }

    /** 解析 state_data JSON 中的 context[] 并转为 A2A Message 列表 */
    private List<Message> extractA2aMessages(String stateData) {
        var messagesArray = StateDataParser.findMessagesArray(stateData);
        if (messagesArray == null || !messagesArray.isArray()) {
            return List.of();
        }
        var result = new ArrayList<Message>();
        for (var m : messagesArray) {
            var role = roleOf(m.path("role").asText(""));
            var parts = partsOf(m);
            if (role == null || parts == null || parts.isEmpty()) {
                continue;
            }
            result.add(new Message(role, parts,
                m.path("id").asText(UUID.randomUUID().toString()),
                taskIdOf(m), null, null, null, null));
        }
        return result;
    }

    /** role 映射：USER → Message.Role.USER，ASSISTANT/AGENT → Message.Role.AGENT，其余 null */
    private Message.Role roleOf(String role) {
        if (role == null) {
            return null;
        }
        return switch (role.toUpperCase()) {
            case "USER" -> Message.Role.USER;
            case "ASSISTANT", "AGENT" -> Message.Role.AGENT;
            default -> null;
        };
    }

    /** 提取 content 中的 text 块为 TextPart 列表（thinking/tool_use/tool_result 跳过） */
    private List<Part<?>> partsOf(JsonNode msg) {
        var text = StateDataParser.extractContentText(msg);
        if (text.isBlank()) {
            return null;
        }
        return List.of(new TextPart(text));
    }

    /** 消息所属任务 id：优先取 metadata.taskId，缺失时为 null（由 SDK 回填） */
    private String taskIdOf(JsonNode msg) {
        var metadata = msg.get("metadata");
        if (metadata != null && metadata.isObject()) {
            var taskId = metadata.get("taskId");
            if (taskId != null && taskId.isTextual()) {
                return taskId.asText();
            }
        }
        return null;
    }

    /** 从 state_data 推断任务状态 */
    private TaskState inferTaskState(String stateData) {
        try {
            var root = MAPPER.readTree(stateData);
            if (root.path("shutdown_interrupted").asBoolean(false)) {
                return TaskState.CANCELED;
            }
            if (root.path("cur_iter").asInt(0) > 0) {
                return TaskState.WORKING;
            }
        } catch (Exception e) {
            log.warn("Infer task state failed: {}", e.getMessage());
        }
        return TaskState.COMPLETED;
    }
}
