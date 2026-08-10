package io.agentmanager.framework.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AgentScope 2.0 state_data JSON 解析工具，供 DebugApiController 与 MySqlTaskStore 共用。
 *
 * <p>state_data 结构（agent_state 表）：
 * <pre>
 * {
 *   "session_id": "...",
 *   "user_id": "...",
 *   "context": [                                    // ← 消息数组（AgentScope 2.0）
 *     {"role": "USER", "content": [{"type": "text", "text": "..."}, ...], "metadata": {...}},
 *     {"role": "ASSISTANT", "content": [{"type": "thinking", ...}, {"type": "text", "text": "..."}]}
 *   ],
 *   "cur_iter": 0,
 *   "shutdown_interrupted": false
 * }
 * </pre>
 */
public final class StateDataParser {

    private StateDataParser() {
    }

    /**
     * BFS 查找 state_data JSON 中的 context/messages 消息数组。
     * 优先 context（AgentScope 2.0），兼容旧格式 messages。
     *
     * @return 消息数组节点；未找到时返回 null
     */
    public static JsonNode findMessagesArray(String stateData) {
        if (stateData == null || stateData.isBlank()) {
            return null;
        }
        try {
            var root = new ObjectMapper().readTree(stateData);
            var queue = new ArrayDeque<JsonNode>();
            queue.add(root);
            while (!queue.isEmpty()) {
                var cur = queue.poll();
                if (cur.isObject()) {
                    var messages = cur.get("context");
                    if (messages == null || !messages.isArray()) {
                        messages = cur.get("messages");
                    }
                    if (messages != null && messages.isArray()) {
                        return messages;
                    }
                    cur.elements().forEachRemaining(queue::add);
                } else if (cur.isArray()) {
                    cur.elements().forEachRemaining(queue::add);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * 从消息节点提取文本内容：
     * content 为字符串直接用；为 ContentBlock 数组时拼接 text 块（thinking/tool 块跳过）。
     */
    public static String extractContentText(JsonNode msg) {
        var content = msg.get("content");
        if (content == null) {
            // 兼容旧格式：无 content 字段时回退 parts 数组
            if (msg.has("parts")) {
                return msg.get("parts").toString();
            }
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            var sb = new StringBuilder();
            for (var block : content) {
                if ("text".equals(block.path("type").asText(""))) {
                    var text = block.path("text").asText("");
                    if (!text.isBlank()) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(text);
                    }
                }
                // thinking/tool_use/tool_result 块跳过，仅展示纯文本
            }
            return sb.toString();
        }
        return "";
    }

    /** 提取消息 role（小写）；缺失时返回 "user" */
    public static String extractRole(JsonNode msg) {
        return msg.path("role").asText("user").toLowerCase();
    }

    /** 将消息数组转换为 {role, content} 列表（供 Debug API 前端展示） */
    public static List<Map<String, Object>> toRoleContentList(JsonNode messagesArray) {
        if (messagesArray == null || !messagesArray.isArray()) {
            return List.of();
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var m : messagesArray) {
            var content = extractContentText(m);
            if (!content.isBlank()) {
                result.add(Map.of("role", extractRole(m), "content", content));
            }
        }
        return result;
    }
}
