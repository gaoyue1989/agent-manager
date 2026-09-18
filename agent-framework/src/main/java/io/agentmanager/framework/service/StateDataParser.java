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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具结果文本默认截断上限（字符）：防止长输出（大文件内容等）撑大 history 响应 */
    public static final int DEFAULT_TOOL_OUTPUT_MAX_CHARS = 8000;

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
            var root = MAPPER.readTree(stateData);
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

    /**
     * 从 AgentState 的 context 提取待人工确认（HITL）的工具调用。
     *
     * <p>判定口径与官方 SDK 一致（{@code ReActAgent.askingToolCalls()}）：只看**最后一条 assistant 消息**
     * 中 {@code state == "asking"} 的 tool_use 块。这是 SDK 自己持久化的挂起态，
     * 相比 confirm_context 表（30 分钟 TTL），它与会话状态同寿命，刷新/重启后依然可恢复。
     *
     * @return 每个元素形如 {tool_call_id, name, input, reply_id}；无挂起时为空列表
     */
    public static List<Map<String, Object>> extractAskingToolCalls(JsonNode messagesArray) {
        if (messagesArray == null || !messagesArray.isArray() || messagesArray.isEmpty()) {
            return List.of();
        }
        // 反向找最后一条 assistant 消息（SDK 同样只看最后一条）
        JsonNode lastAssistant = null;
        for (int i = messagesArray.size() - 1; i >= 0; i--) {
            var m = messagesArray.get(i);
            if ("assistant".equals(extractRole(m))) {
                lastAssistant = m;
                break;
            }
        }
        if (lastAssistant == null) {
            return List.of();
        }
        var replyId = lastAssistant.path("metadata").path(METADATA_CONFIRM_REQUEST_REPLY_ID).asText("");
        var result = new ArrayList<Map<String, Object>>();
        var content = lastAssistant.path("content");
        if (!content.isArray()) {
            return List.of();
        }
        for (var c : content) {
            if (!"tool_use".equals(c.path("type").asText())) {
                continue;
            }
            if (!"asking".equals(c.path("state").asText(""))) {
                continue;
            }
            var call = new java.util.LinkedHashMap<String, Object>();
            call.put("tool_call_id", c.path("id").asText(""));
            call.put("name", c.path("name").asText(""));
            call.put("input", c.path("input"));
            call.put("reply_id", replyId);
            result.add(call);
        }
        return result;
    }

    /** 官方 SDK 的 HITL 关联 replyId 元数据键（与 Msg.METADATA_CONFIRM_REQUEST_REPLY_ID 一致） */
    private static final String METADATA_CONFIRM_REQUEST_REPLY_ID = "agentscope_confirm_request_reply_id";

    /**
     * 提取 state_data 根对象上的运行时身份（{@code session_id} / {@code user_id}）。
     *
     * <p>Channel 流程下这两个值就是恢复 agent 所需的 RuntimeContext 键：
     * sessionId 为网关确定性派生的 {@code gw-hash}，userId 为 peer（rawSessionId）。
     * SDK 把它们连同 context 一起持久化，因此恢复不依赖任何外部表。
     *
     * @return {sessionId, userId}；缺失时对应值为空串
     */
    public static Map<String, String> extractRuntimeIdentity(String stateData) {
        var result = new java.util.HashMap<String, String>();
        result.put("session_id", "");
        result.put("user_id", "");
        if (stateData == null || stateData.isBlank()) {
            return result;
        }
        try {
            // state_data 可能是单条对象或（多条 item_index 拼成的）数组，取第一个含 session_id 的对象
            var root = MAPPER.readTree(stateData);
            var queue = new ArrayDeque<JsonNode>();
            queue.add(root);
            while (!queue.isEmpty()) {
                var cur = queue.poll();
                if (cur.isObject() && cur.hasNonNull("session_id")) {
                    result.put("session_id", cur.path("session_id").asText(""));
                    result.put("user_id", cur.path("user_id").asText(""));
                    return result;
                }
                if (cur.isObject() || cur.isArray()) {
                    cur.elements().forEachRemaining(queue::add);
                }
            }
        } catch (Exception e) {
            // 解析失败按缺失处理：调用方回落到既有 key 推导逻辑
        }
        return result;
    }

    /** 提取消息 role（小写）；缺失时返回 "user" */
    public static String extractRole(JsonNode msg) {
        return msg.path("role").asText("user").toLowerCase();
    }

    // ===== 工具结果文本的敏感值遮掩 =====
    // 工具 output 可能回显敏感配置（实测 get_service_status 的 output 含 LLM_API_KEY 明文）。
    // 确认卡对 input 已有 maskInput（结构化遮掩）；output 是纯文本，这里做同语义的文本级遮掩，
    // 在截断之前应用——否则截断边界可能恰好保住敏感片段。

    /** 遮掩标记（与前端 maskInput 的展示文案一致） */
    static final String MASKED = "••••••（已遮掩）";

    /**
     * 键值形态：敏感键（含引号/中划线/下划线变体）+ 分隔符 + 可选引号 + 值 + 可选引号。
     * 值限定为「≥8 位凭据字符集且非纯数字」——纯数字的用量统计（totalTokens 等）不遮。
     * 引号包在 group(4)（首尾各一，可缺）：替换时整体吞掉，遮掩值统一为裸标记。
     */
    private static final java.util.regex.Pattern SENSITIVE_KV = java.util.regex.Pattern.compile(
        "(?i)([\"'\\w-]*(?:password|passwd|pwd|secret|token|credential|authorization|api[_-]?key"
            + "|access[_-]?key|private[_-]?key|密钥|密码)[\"'\\w-]*\\s*[:：=]\\s*)"
            + "([\"']?)"
            + "([A-Za-z0-9_\\-./+=]{8,})"
            + "([\"']?)");

    /** 平台 token 形态：tp- 前缀 + ≥16 位随机串 */
    private static final java.util.regex.Pattern PLATFORM_TOKEN =
        java.util.regex.Pattern.compile("tp-[A-Za-z0-9]{16,}");

    /** Bearer 凭据形态 */
    private static final java.util.regex.Pattern BEARER_TOKEN =
        java.util.regex.Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{16,}");

    /**
     * 遮掩工具结果文本中的敏感值（密钥键值对、平台 token、Bearer 凭据）。
     * 纯数字值（token 用量统计等）不遮，避免破坏 usage 类正常输出。
     */
    public static String maskSensitiveText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        var out = SENSITIVE_KV.matcher(text).replaceAll(m -> {
            var value = m.group(3);
            boolean allDigits = !value.isEmpty()
                && value.chars().allMatch(Character::isDigit);
            // 引号（group 2/4）随值一并吞掉：遮掩值统一为裸标记，JSON 风格输出更整洁
            return allDigits ? m.group() : m.group(1) + MASKED;
        });
        out = PLATFORM_TOKEN.matcher(out).replaceAll(MASKED);
        out = BEARER_TOKEN.matcher(out).replaceAll(mr -> mr.group(1) + MASKED);
        return out;
    }

    /**
     * 将消息数组转换为 {role, content, tool_calls?} 列表（供 Debug API / history 前端展示）。
     *
     * <p>消息级事实以 AgentState 为权威来源（官方 SDK 自动持久化）：tool_use 块带
     * {@code state}（ToolCallState：pending/asking/allowed/submitted/finished），
     * tool_result 块带 {@code state}（ToolResultState）与 {@code output}。
     * 本方法把它们按 id 配对合并进 assistant 消息的 tool_calls，使历史回放与实时流语义一致。
     */
    public static List<Map<String, Object>> toRoleContentList(JsonNode messagesArray) {
        return toRoleContentList(messagesArray, DEFAULT_TOOL_OUTPUT_MAX_CHARS);
    }

    /**
     * 同上，但可指定工具结果文本的截断上限（字符）。
     *
     * @param toolOutputMaxChars 单个工具结果保留的最大字符数；{@code <= 0} 表示不截断
     */
    public static List<Map<String, Object>> toRoleContentList(JsonNode messagesArray, int toolOutputMaxChars) {
        if (messagesArray == null || !messagesArray.isArray()) {
            return List.of();
        }
        // 先收集全量 tool_result（按 tool_use id 索引），再在遍历消息时与 tool_use 配对：
        // 结果块位于后续的 TOOL 角色消息里，需要一次性建立映射才能合并到产出它的 assistant 消息上
        var resultsByCallId = collectToolResults(messagesArray, toolOutputMaxChars);

        var result = new ArrayList<Map<String, Object>>();
        for (var m : messagesArray) {
            var content = extractContentText(m);
            var tools = extractToolCalls(m, resultsByCallId);
            // 仅保留有文本内容或工具调用的消息（跳过纯 tool_result 等）
            if (content.isBlank() && tools.isEmpty()) {
                continue;
            }
            var msg = new java.util.LinkedHashMap<String, Object>();
            msg.put("role", extractRole(m));
            msg.put("content", content);
            if (!tools.isEmpty()) {
                msg.put("tool_calls", tools);
            }
            result.add(msg);
        }
        return result;
    }

    /** 已截断的工具结果（toolCallId → {state, output, truncated}） */
    private static Map<String, Map<String, Object>> collectToolResults(JsonNode messagesArray, int maxChars) {
        var byId = new java.util.HashMap<String, Map<String, Object>>();
        for (var m : messagesArray) {
            var content = m.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (var c : content) {
                if (!"tool_result".equals(c.path("type").asText())) {
                    continue;
                }
                var id = c.path("id").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                var entry = new java.util.LinkedHashMap<String, Object>();
                var state = c.path("state").asText("");
                if (!state.isBlank()) {
                    // 官方 ToolResultState 的序列化值：success/error/interrupted/denied/running
                    entry.put("state", state);
                }
                var output = maskSensitiveText(extractOutputText(c.get("output")));
                if (!output.isEmpty()) {
                    if (maxChars > 0 && output.length() > maxChars) {
                        // 截断保留前 maxChars 字符，并标记供前端提示「已截断」
                        entry.put("output", output.substring(0, maxChars));
                        entry.put("output_truncated", true);
                        entry.put("output_full_length", output.length());
                    } else {
                        entry.put("output", output);
                    }
                }
                byId.put(id, entry);
            }
        }
        return byId;
    }

    /**
     * 提取工具结果文本。
     *
     * <p>输出形态随工具而异：字符串、ContentBlock 数组（text/image 等）、或任意 JSON。
     * 文本块拼接为纯文本；非文本块与被调用方按需降级为紧凑 JSON（图片等二进制不内联进历史）。
     */
    public static String extractOutputText(JsonNode output) {
        if (output == null || output.isMissingNode() || output.isNull()) {
            return "";
        }
        if (output.isTextual()) {
            return output.asText();
        }
        if (output.isArray()) {
            var sb = new StringBuilder();
            boolean hasText = false;
            for (var block : output) {
                if ("text".equals(block.path("type").asText(""))) {
                    var text = block.path("text").asText("");
                    if (!text.isBlank()) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(text);
                        hasText = true;
                    }
                }
            }
            if (hasText) {
                return sb.toString();
            }
            // 无文本块（如纯图片结果）：回落为紧凑 JSON，避免历史里彻底丢失该结果的形状
            return output.toString();
        }
        return output.isValueNode() ? output.asText() : output.toString();
    }

    /**
     * 提取消息中的工具调用，并把配对的执行结果合并进来。
     *
     * <p>合并后的形状（与实时流渲染对齐，前端无需区分来源）：
     * <pre>
     *   {id, name, input, state?, output?, output_truncated?, output_full_length?}
     * </pre>
     * {@code state} 优先取工具结果状态（更能表达"执行结果"），
     * 无结果时回落到 tool_use 自身的 ToolCallState（如 HITL 拦截后的 {@code asking}）。
     */
    private static List<Map<String, Object>> extractToolCalls(JsonNode msg, Map<String, Map<String, Object>> resultsByCallId) {
        var result = new ArrayList<Map<String, Object>>();
        var content = msg.path("content");
        if (!content.isArray()) {
            return result;
        }
        for (var c : content) {
            if (!"tool_use".equals(c.path("type").asText())) {
                continue;
            }
            var call = new java.util.LinkedHashMap<String, Object>();
            var id = c.path("id").asText("");
            call.put("id", id);
            call.put("name", c.path("name").asText(""));
            call.put("input", c.path("input"));

            var useState = c.path("state").asText("");
            var outcome = resultsByCallId.get(id);
            if (outcome != null) {
                call.putAll(outcome);
            }
            if (!call.containsKey("state") && !useState.isBlank()) {
                call.put("state", useState);
            }
            result.add(call);
        }
        return result;
    }
}
