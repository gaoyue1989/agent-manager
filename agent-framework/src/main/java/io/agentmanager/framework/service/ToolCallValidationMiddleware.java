package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import reactor.core.publisher.Flux;

/**
 * 校验 ToolUseBlock 完整性的中间件。
 *
 * <p>背景：vLLM / Qwen3 等模型在流式返回 tool call 时，有时不会发送 function name，
 * 导致 SDK 的 {@code ToolCallsAccumulator} 产出 {@code name=null} 的 ToolUseBlock。
 * SDK 的 {@code OpenAIMessageConverter} 遇到 null id/name 会直接跳过，
 * LLM 不知道自己调过工具，引发行为异常。
 *
 * <p>本中间件在 acting 阶段拦截，对畸形的 ToolUseBlock：
 * <ul>
 *   <li>记录 WARN 日志（含原始 content 片段，便于排查模型输出问题）</li>
 *   <li>向 agent 上下文注入错误 ToolResultBlock，告知 LLM 该 tool call 无效</li>
 *   <li>向前端发送合成事件，保持 SSE 流的完整性</li>
 * </ul>
 *
 * <p>此外对 name/id 合法但参数有瑕疵的 tool call 做规范化（{@link #normalizeToolCall}），
 * 在 SDK schema 校验前修复，降低 {@code Parameter validation failed ... 缺少所需属性}
 * （如 write_file 缺 path）的出现频率：
 * <ul>
 *   <li>别名归一：path ↔ file_path/filepath/filename/file、content ↔ text/contents/body 等</li>
 *   <li>content 重建：SDK 流式累积把非法参数 JSON 回退成 "{}" 时，用 input map 还原 content</li>
 *   <li>不可自愈（参数 JSON 非空但非法且无可用参数）时给出直白错误提示</li>
 * </ul>
 */
public class ToolCallValidationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolCallValidationMiddleware.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 畸形工具调用的占位名，仅用于事件流（不会真正执行） */
    private static final String MALFORMED_TOOL_PLACEHOLDER = "_malformed_tool_call";

    /** 参数 JSON 不可解析的占位名，仅用于事件流（不会真正执行） */
    private static final String INVALID_ARGS_TOOL_PLACEHOLDER = "_invalid_tool_args";

    /**
     * 文件类工具的常见参数别名 → 规范参数名。
     *
     * <p>来源：模型（尤其经第三方 OpenAI 兼容网关时）常把 path 写成 file_path/filepath/
     * filename/file、把 content 写成 text/contents/body 等，触发 SDK 的 required 校验失败
     * （形如 {@code Parameter validation failed ... 未找到所需属性"path"}）。仅在规范参数
     * 缺失时按此映射补齐，规范参数已存在则不动，避免误伤。
     */
    private static final Map<String, Map<String, String>> TOOL_ARG_ALIASES = Map.of(
            "write_file", Map.ofEntries(
                    Map.entry("file_path", "path"),
                    Map.entry("filepath", "path"),
                    Map.entry("filename", "path"),
                    Map.entry("file", "path"),
                    Map.entry("text", "content"),
                    Map.entry("contents", "content"),
                    Map.entry("body", "content")),
            "read_file", Map.ofEntries(
                    Map.entry("file_path", "path"),
                    Map.entry("filepath", "path"),
                    Map.entry("filename", "path"),
                    Map.entry("file", "path")),
            "edit_file", Map.ofEntries(
                    Map.entry("file_path", "path"),
                    Map.entry("filepath", "path"),
                    Map.entry("filename", "path"),
                    Map.entry("file", "path"),
                    Map.entry("old", "old_string"),
                    Map.entry("old_str", "old_string"),
                    Map.entry("new", "new_string"),
                    Map.entry("new_str", "new_string")),
            "list_files", Map.ofEntries(
                    Map.entry("file_path", "path"),
                    Map.entry("filepath", "path"),
                    Map.entry("filename", "path"),
                    Map.entry("file", "path"),
                    Map.entry("dir", "path"),
                    Map.entry("directory", "path")),
            "glob_files", Map.ofEntries(
                    Map.entry("file_path", "path"),
                    Map.entry("filepath", "path"),
                    Map.entry("glob", "pattern")),
            "grep_files", Map.ofEntries(
                    Map.entry("file_path", "path"),
                    Map.entry("filepath", "path"),
                    Map.entry("query", "pattern"),
                    Map.entry("regex", "pattern")));

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {

        List<ToolUseBlock> allCalls = input.toolCalls();
        if (allCalls == null || allCalls.isEmpty()) {
            return next.apply(input);
        }

        List<ToolUseBlock> valid = new ArrayList<>();
        List<ToolUseBlock> malformed = new ArrayList<>();
        List<RejectedCall> rejected = new ArrayList<>();
        boolean changed = false;

        for (ToolUseBlock tub : allCalls) {
            if (isMalformed(tub)) {
                malformed.add(tub);
                changed = true;
                continue;
            }
            NormalizedCall nc = normalizeToolCall(tub);
            if (nc.rejectionMessage() != null) {
                rejected.add(new RejectedCall(tub, nc.rejectionMessage()));
                changed = true;
            } else {
                if (nc.block() != tub) {
                    changed = true;
                }
                valid.add(nc.block());
            }
        }

        // 无任何变化：零开销直通（不重建 ActingInput）
        if (!changed) {
            return next.apply(input);
        }

        // 仅做了参数规范化、无拒绝：直接执行规范化后的 tool calls
        if (malformed.isEmpty() && rejected.isEmpty()) {
            return next.apply(new ActingInput(valid));
        }

        // --- 处理畸形/参数不可用的 tool calls ---
        List<AgentEvent> syntheticEvents = new ArrayList<>();
        AgentState state = agent.getAgentState();

        for (ToolUseBlock tub : malformed) {
            emitRejection(agent, state, syntheticEvents, tub,
                    MALFORMED_TOOL_PLACEHOLDER, buildMalformedErrorMessage(tub));
        }
        for (RejectedCall rc : rejected) {
            emitRejection(agent, state, syntheticEvents, rc.tub(),
                    INVALID_ARGS_TOOL_PLACEHOLDER, rc.message());
        }

        // --- 拼接：合成事件 + 正常事件 ---
        if (valid.isEmpty()) {
            // 所有 tool call 都不可执行，只返回合成事件
            return Flux.fromIterable(syntheticEvents);
        }

        // 部分有效：先返回合成事件，再执行有效的 tool calls
        return Flux.concat(
                Flux.fromIterable(syntheticEvents),
                next.apply(new ActingInput(valid)));
    }

    /**
     * 合成并注入一次工具拒绝：向前端发 ToolCall/ToolResult 事件保持 SSE 流连续，
     * 同时把错误 ToolResultBlock 写入 agent 上下文，引导模型自纠。
     */
    private static void emitRejection(Agent agent, AgentState state,
            List<AgentEvent> syntheticEvents, ToolUseBlock tub,
            String placeholderName, String errorMsg) {
        String toolId = resolveToolId(tub);
        log.warn("[tool-call-validation] Rejecting ToolUseBlock: "
                        + "id={}, name={}, content={}, reason={}",
                toolId, tub.getName(), truncate(tub.getContent(), 200), errorMsg);

        String replyId = UUID.randomUUID().toString().replace("-", "");
        syntheticEvents.add(new ToolCallStartEvent(replyId, toolId, placeholderName));
        syntheticEvents.add(new ToolCallEndEvent(replyId, toolId, placeholderName));
        syntheticEvents.add(new ToolResultStartEvent(replyId, toolId, placeholderName));
        syntheticEvents.add(new ToolResultTextDeltaEvent(replyId, toolId, placeholderName, errorMsg));
        syntheticEvents.add(new ToolResultEndEvent(replyId, toolId, placeholderName, ToolResultState.ERROR));

        ToolResultBlock errorResult = ToolResultBlock.builder()
                .id(toolId)
                .name(placeholderName)
                .output(List.of(TextBlock.builder().text(errorMsg).build()))
                .state(ToolResultState.ERROR)
                .build();

        Msg errorResultMsg = ToolResultMessageBuilder.buildToolResultMsg(
                errorResult, tub, agent.getName());
        state.contextMutable().add(errorResultMsg);
    }

    /** 规范化结果：block 非空 = 可执行（可能已修复）；rejectionMessage 非空 = 拒绝并带原因 */
    record NormalizedCall(ToolUseBlock block, String rejectionMessage) {
        static NormalizedCall ok(ToolUseBlock block) {
            return new NormalizedCall(block, null);
        }

        static NormalizedCall reject(String message) {
            return new NormalizedCall(null, message);
        }
    }

    /** 被拒绝的 tool call 及原因 */
    private record RejectedCall(ToolUseBlock tub, String message) {
    }

    /**
     * 参数规范化：在 SDK schema 校验前修复两类可自愈的 tool call 变形。
     *
     * <ol>
     *   <li><b>别名归一</b>：模型常把 path 写成 file_path/filepath/filename/file、把
     *       content 写成 text/contents/body 等，按 {@link #TOOL_ARG_ALIASES} 映射为规范名。</li>
     *   <li><b>content 重建</b>：SDK 流式累积在原始参数 JSON 非法/截断时会把 content 回退成
     *       "{}"（{@code ToolCallsAccumulator.build}），而真实参数仍在 input map；若 content
     *       为空/非法但 input 非空，用 input 重新序列化 content，使校验口径与实际执行参数一致，
     *       避免误报"缺少所需属性 path"。</li>
     * </ol>
     *
     * <p>零参数工具（content 空、input 空）保持原样交给 SDK 校验，不做拒绝。
     *
     * @return 规范化结果；content 非空但非合法 JSON 且 input 为空时返回拒绝消息
     */
    static NormalizedCall normalizeToolCall(ToolUseBlock tub) {
        if (tub == null) {
            return NormalizedCall.reject("Error: tool call was null.");
        }
        Map<String, Object> input = tub.getInput() != null ? tub.getInput() : Map.of();
        Map<String, Object> normalizedInput = normalizeAliases(tub.getName(), input);
        boolean inputChanged = normalizedInput != input;

        String content = tub.getContent();
        JsonNode contentNode = parseObjectNode(content);
        boolean contentUsable = contentNode != null;
        // content 是空对象 "{}" 但 input 非空：SDK 累积器把非法原始 JSON 回退成 "{}" 的形态，
        // content 已不能代表真实参数，需要用 input 重建
        boolean contentEmptyObject = contentUsable && contentNode.isEmpty();

        if (contentUsable && !contentEmptyObject && !inputChanged) {
            return NormalizedCall.ok(tub);
        }

        boolean inputEmpty = normalizedInput.isEmpty();
        if (!contentUsable && inputEmpty) {
            if (isBlank(content)) {
                // content 为空且无参数：零参数工具的正常形态，交给 SDK 校验
                return NormalizedCall.ok(tub);
            }
            // content 非空但非合法 JSON，且无可用参数 → 无法自愈，明确拒绝
            return NormalizedCall.reject(buildInvalidArgsErrorMessage(tub, content));
        }

        // input 非空（或别名归一后需同步 content）：用 input 重建 content
        String finalContent = inputEmpty ? "{}" : serialize(normalizedInput);
        return NormalizedCall.ok(new ToolUseBlock(
                tub.getId(), tub.getName(), normalizedInput,
                finalContent, tub.getMetadata(), tub.getState()));
    }

    /**
     * 按工具把别名参数映射为规范名；仅在规范名缺失时替换，返回原 map 实例表示无需改动。
     */
    private static Map<String, Object> normalizeAliases(String toolName, Map<String, Object> input) {
        Map<String, String> aliases = toolName != null ? TOOL_ARG_ALIASES.get(toolName) : null;
        if (aliases == null || input == null || input.isEmpty()) {
            return input;
        }
        Map<String, Object> out = null;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String canonical = aliases.get(entry.getKey());
            if (canonical == null) {
                continue;
            }
            if (out == null) {
                out = new LinkedHashMap<>(input);
            }
            // 规范名已存在时丢弃冗余别名，否则把别名值搬到规范名下
            if (!out.containsKey(canonical)) {
                out.put(canonical, entry.getValue());
            }
            out.remove(entry.getKey());
        }
        return out != null ? out : input;
    }

    /** content 是否为合法的 JSON 对象（允许空对象） */
    static boolean isJsonObject(String json) {
        return parseObjectNode(json) != null;
    }

    /** 解析为 JSON 对象节点；非对象/空串/非法 JSON 返回 null */
    private static JsonNode parseObjectNode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String serialize(Map<String, Object> map) {
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 参数 JSON 不可解析时返回给 LLM 的提示（比 SDK 的"缺少 path"更贴近真实原因） */
    private static String buildInvalidArgsErrorMessage(ToolUseBlock tub, String rawContent) {
        return "Error: The arguments for tool '" + tub.getName()
                + "' could not be parsed as a JSON object and no parameters were extracted. "
                + "Please retry with a valid JSON argument object. Original arguments: "
                + truncate(rawContent, 500);
    }

    /**
     * 判断 ToolUseBlock 是否畸形。
     *
     * <p>以下情况视为畸形：
     * <ul>
     *   <li>name 为 null 或空字符串</li>
     *   <li>id 为 null 或空字符串（部分模型/部署确实不返回 id）</li>
     *   <li>name 以 "__" 开头（SDK 内部占位符，如 "__fragment__"）</li>
     * </ul>
     */
    static boolean isMalformed(ToolUseBlock tub) {
        if (tub == null) {
            return true;
        }
        String name = tub.getName();
        if (name == null || name.isEmpty()) {
            return true;
        }
        if (name.startsWith("__")) {
            return true;
        }
        // id 为 null 我们也视为畸形 —— 没有id的tool call无法被converter正确处理
        String id = tub.getId();
        if (id == null || id.isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * 为畸形 ToolUseBlock 生成唯一 id（若原 id 为空）。
     */
    private static String resolveToolId(ToolUseBlock tub) {
        String id = tub.getId();
        if (id != null && !id.isEmpty()) {
            return id;
        }
        return "malformed_" + System.currentTimeMillis() + "_"
                + Integer.toHexString(tub.hashCode());
    }

    /**
     * 构建返回给 LLM 的错误信息，引导模型重新生成完整的 tool call。
     */
    private static String buildMalformedErrorMessage(ToolUseBlock tub) {
        StringBuilder sb = new StringBuilder();
        sb.append("Error: The tool call was malformed and could not be executed. ");

        if (tub.getName() == null || tub.getName().isEmpty()) {
            sb.append("The function name was missing. ");
        } else if (tub.getName().startsWith("__")) {
            sb.append("The function name was '").append(tub.getName())
                    .append("' (placeholder, not a real function). ");
        }

        if (tub.getId() == null || tub.getId().isEmpty()) {
            sb.append("The tool call id was missing. ");
        }

        sb.append("Please retry with a complete and valid tool call.");

        // 附上原始 content 片段帮助 LLM 自纠
        if (tub.getContent() != null && !tub.getContent().isEmpty()) {
            sb.append(" Original partial arguments: ")
                    .append(truncate(tub.getContent(), 500));
        }

        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "<null>";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...(truncated, total " + s.length() + " chars)";
    }
}
