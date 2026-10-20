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
            // input 转 Map：JsonNode 会破坏 AgentStateReader 重建 ToolUseBlock 的 instanceof Map 判断
            //（重建时 input 恒为空 Map，恢复执行参数丢失）；转成普通 Map 供下游直接使用
            var inputNode = c.path("input");
            if (inputNode.isObject()) {
                try {
                    call.put("input", MAPPER.convertValue(inputNode,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
                } catch (Exception e) {
                    call.put("input", new java.util.LinkedHashMap<String, Object>());
                }
            } else {
                call.put("input", new java.util.LinkedHashMap<String, Object>());
            }
            // 透传块上的 content 字符串（SDK 挂起时落 state 的原始参数 JSON），
            // 供重建 ToolUseBlock 时回填——content=null 会导致 ToolValidator 抛
            // 'Schema validation error: argument "content" is null'（HITL 恢复失败根因）
            if (c.hasNonNull("content") && c.path("content").isTextual()) {
                call.put("content", c.path("content").asText());
            }
            call.put("reply_id", replyId);
            result.add(call);
        }
        return result;
    }

    /** 官方 SDK 的 HITL 关联 replyId 元数据键（与 Msg.METADATA_CONFIRM_REQUEST_REPLY_ID 一致） */
    private static final String METADATA_CONFIRM_REQUEST_REPLY_ID = "agentscope_confirm_request_reply_id";

    /** 把 Map 序列化为 JSON 字符串（ToolUseBlock.content 回填用）；失败返回 null（fail-soft） */
    public static String toJsonString(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

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

    /** SDK 合成消息元数据键（与 {@code Msg.METADATA_SYNTHETIC} 一致） */
    static final String METADATA_SYNTHETIC = "agentscope_synthetic";

    /** SDK 合成提醒消息的固定 name（TaskReminder/Subagents 中间件均用 "system"） */
    static final String SYNTHETIC_REMINDER_NAME = "system";

    /** HITL 确认恢复控制消息的 metadata 键（与 {@code Msg.METADATA_CONFIRM_RESULTS} 一致） */
    static final String METADATA_CONFIRM_RESULTS = "agentscope_confirm_results";

    /**
     * 提取用户消息 metadata 中记录的上传文件 id 列表。
     *
     * <p>写入侧为 {@code UploadWorkspaceInjector.METADATA_FILE_IDS}（发送时随用户消息持久化）；
     * 仅供 history 层据此把上传文件卡片挂回对应用户消息。缺失/非数组返回空列表。
     */
    static List<String> extractFileIds(JsonNode msg) {
        if (msg == null) {
            return List.of();
        }
        var node = msg.path("metadata").path(
            io.agentmanager.framework.service.UploadWorkspaceInjector.METADATA_FILE_IDS);
        if (!node.isArray()) {
            return List.of();
        }
        var ids = new ArrayList<String>();
        for (var n : node) {
            var s = n.asText("");
            if (!s.isBlank()) {
                ids.add(s);
            }
        }
        return ids;
    }

    /**
     * 判定消息是否为 SDK 注入的合成提醒，而非用户/助手真实产出。
     *
     * <p>官方 SDK 会把后台子 agent 任务投递提醒以 {@code role=USER} + {@code name=system} +
     * {@code metadata.agentscope_synthetic=true} 的形式**持久化进 AgentState.context**
     * （{@code SubagentsMiddleware.onReasoning} 的 {@code buildDeliveryReminder}），
     * 内容为 {@code <system-reminder>…</system-reminder>}。它没有真实用户输入/模型回复语义，
     * history 回放时不得按 user 呈现（否则用户会看到「并非自己提问、却以 user 出现」的消息）。
     *
     * <p>判据双保险：优先信 {@code metadata.agentscope_synthetic=true}；老数据/元数据缺失时，
     * 回落到「role=user 且 name=system」——框架自身的用户消息 name 恒为 userId（Channel）
     * 或 agent 收发名，不可能是 "system"，误伤面为零。
     */
    public static boolean isSyntheticReminder(JsonNode msg) {
        if (msg == null) {
            return false;
        }
        var metadata = msg.path("metadata");
        if (metadata.isObject() && metadata.path(METADATA_SYNTHETIC).asBoolean(false)) {
            return true;
        }
        return "user".equals(extractRole(msg))
            && SYNTHETIC_REMINDER_NAME.equals(msg.path("name").asText(""));
    }

    /**
     * 判定消息是否为 HITL 确认后的恢复控制消息，而非用户真实提问。
     *
     * <p>框架在人工确认工具调用后构造 {@code buildResumeMsg}（role=USER、name="user"、
     * metadata 携带 {@code agentscope_confirm_results}，文案形如「人工已批准上述工具调用…」），
     * 该消息被 SDK 持久化进 AgentState.context 并随归档轨落库。它没有用户输入语义，
     * history 回放时不得以 user 呈现（否则用户会看到「并非自己提问、却以 user 出现」的消息）。
     *
     * <p>判据用 metadata 键（内容/name 都可能与真实提问撞车，不可靠）。
     */
    public static boolean isConfirmResume(JsonNode msg) {
        if (msg == null) {
            return false;
        }
        var metadata = msg.path("metadata");
        return metadata.isObject() && metadata.has(METADATA_CONFIRM_RESULTS);
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
     *
     * <p>SDK 注入的合成提醒（后台子任务投递等，见 {@link #isSyntheticReminder}）不属对话轮次，
     * 一律跳过，避免以 {@code role=user} 冒充用户输入上屏。
     *
     * <p>user 消息的 {@code @Skill} 注入区块（展开的整篇 SKILL.md）在展示层剥离
     * （{@link SkillInjectionService#stripInjectedSkills}）：只显示用户自己敲的内容。
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
            // SDK 合成提醒（后台子任务投递等）无用户/助手语义，跳过，防止冒充 user 上屏
            if (isSyntheticReminder(m)) {
                continue;
            }
            // HITL 确认恢复控制消息（"人工已批准…"）同样无用户输入语义，不属对话轮次，跳过
            if (isConfirmResume(m)) {
                continue;
            }
            var role = extractRole(m);
            // user 消息的可见文本只保留用户自己敲的内容：
            //  - @Skill 注入区块（整篇 SKILL.md）为模型上下文，展示层剥离
            //  - 上传文件的路径提示为框架自动追加，展示层剥离（文件卡片另经 files 字段下发）
            var content = "user".equals(role)
                ? UploadWorkspaceInjector.stripUploadHints(
                    SkillInjectionService.stripInjectedSkills(extractContentText(m)))
                : extractContentText(m);
            // 用户上传文件 id（写入侧 UploadWorkspaceInjector.METADATA_FILE_IDS）：供 history
            // 层把上传卡片挂回本用户消息。纯文件轮（无文本）也借此保留，不被下面的"空消息"剪掉
            var fileIds = "user".equals(role) ? extractFileIds(m) : List.<String>of();
            var tools = extractToolCalls(m, resultsByCallId);
            // 仅保留有文本内容/工具调用/上传文件的消息（跳过纯 tool_result 等）
            if (content.isBlank() && tools.isEmpty() && fileIds.isEmpty()) {
                continue;
            }
            var msg = new java.util.LinkedHashMap<String, Object>();
            msg.put("role", role);
            msg.put("content", content);
            if (!fileIds.isEmpty()) {
                msg.put("file_ids", fileIds);
            }
            // 消息标识与名（history 双源合并按 msg_id 对齐归档行、按 name 识别压缩摘要消息；
            // 缺失时不写键，保持旧响应形态不变）
            var msgId = m.path("id").asText("");
            if (!msgId.isBlank()) {
                msg.put("msg_id", msgId);
            }
            var name = m.path("name").asText("");
            if (!name.isBlank()) {
                msg.put("name", name);
            }
            if (!tools.isEmpty()) {
                msg.put("tool_calls", tools);
            }
            // 有序块序列（只增不改的附加字段）：content/tool_calls 是两条平行提取，彼此先后已丢，
            // Debug Console 据此按真实发生顺序渲染（缺省时前端退回「工具组在上」旧布局）
            var blocks = extractOrderedBlocks(m);
            if (!blocks.isEmpty()) {
                msg.put("blocks", blocks);
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
            var name = c.path("name").asText("");
            call.put("name", name);
            var input = c.path("input");
            call.put("input", input);
            // 与 SSE 的 tool_call_summary 同一套人可读文案（前端实时流用它替换原始工具名），
            // 历史回放直接携带该字段，避免刷新后工具行退回 write_file / mcp__x__y 这类原始名
            if (!name.isBlank()) {
                call.put("summary", ToolSummaryGenerator.callSummary(name, input.toString()));
            }

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

    /**
     * 按 {@code Msg.content} 的原始块序产出「文本块 / 工具块」有序序列。
     *
     * <p>{@link #extractContentText} 与 {@link #extractToolCalls} 是两条平行提取：文本块被拼成一个
     * 字符串、工具块被收成一个数组，两者的相对先后在提取第一步就丢了，前端只能约定「工具组在上、
     * 文本在下」——模型的开场白会被渲染到它自己的工具步骤后面。本方法保留块序补回该信息。
     *
     * <p>thinking 块不上屏、tool_result 块位于后续 TOOL 角色消息（结果仍由
     * {@link #collectToolResults} 配对合并到 tool_calls 上），两者均不进 blocks。
     *
     * <p>只处理 assistant 消息：文本与工具块交错只发生在 assistant 侧，
     * 其它角色（user 的纯文本、tool 的结果）加了也是噪音。
     *
     * @return 元素形如 {@code {type:"text", text}} 或 {@code {type:"tool", id?, name}}；
     *         content 非数组（旧格式字符串 / 缺失）时为空列表，调用方据此不写 blocks 键
     */
    private static List<Map<String, Object>> extractOrderedBlocks(JsonNode msg) {
        var content = msg.path("content");
        if (!content.isArray() || !"assistant".equals(extractRole(msg))) {
            return List.of();
        }
        var blocks = new ArrayList<Map<String, Object>>();
        for (var block : content) {
            var type = block.path("type").asText("");
            if ("text".equals(type)) {
                var text = block.path("text").asText("");
                if (text.isBlank()) {
                    continue;
                }
                var b = new java.util.LinkedHashMap<String, Object>();
                b.put("type", "text");
                b.put("text", text);
                blocks.add(b);
            } else if ("tool_use".equals(type)) {
                var b = new java.util.LinkedHashMap<String, Object>();
                b.put("type", "tool");
                var id = block.path("id").asText("");
                if (!id.isEmpty()) {
                    b.put("id", id);
                }
                b.put("name", block.path("name").asText(""));
                blocks.add(b);
            }
        }
        return blocks;
    }
}
