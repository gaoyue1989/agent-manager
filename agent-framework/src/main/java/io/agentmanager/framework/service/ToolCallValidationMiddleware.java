package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
public class ToolCallValidationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolCallValidationMiddleware.class);

    /** 畸形工具调用的占位名，仅用于事件流（不会真正执行） */
    private static final String MALFORMED_TOOL_PLACEHOLDER = "_malformed_tool_call";

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {

        List<ToolUseBlock> allCalls = input.toolCalls();
        if (allCalls == null || allCalls.isEmpty()) {
            return next.apply(input);
        }

        List<ToolUseBlock> valid = new ArrayList<>();
        List<ToolUseBlock> malformed = new ArrayList<>();

        for (ToolUseBlock tub : allCalls) {
            if (isMalformed(tub)) {
                malformed.add(tub);
            } else {
                valid.add(tub);
            }
        }

        if (malformed.isEmpty()) {
            return next.apply(input);
        }

        // --- 处理畸形的 tool calls ---
        List<AgentEvent> syntheticEvents = new ArrayList<>();
        AgentState state = agent.getAgentState();

        for (ToolUseBlock tub : malformed) {
            String toolId = resolveToolId(tub);
            String truncatedContent = truncate(tub.getContent(), 200);

            log.warn("[tool-call-validation] Skipping malformed ToolUseBlock: "
                    + "id={}, name={}, content={}",
                    tub.getId(), tub.getName(), truncatedContent);

            // 1. 合成前端事件（保持 SSE 流连续）
            String replyId = UUID.randomUUID().toString().replace("-", "");
            syntheticEvents.add(new ToolCallStartEvent(replyId, toolId, MALFORMED_TOOL_PLACEHOLDER));
            syntheticEvents.add(new ToolCallEndEvent(replyId, toolId, MALFORMED_TOOL_PLACEHOLDER));

            String errorMsg = buildMalformedErrorMessage(tub);

            syntheticEvents.add(new ToolResultStartEvent(replyId, toolId, MALFORMED_TOOL_PLACEHOLDER));
            syntheticEvents.add(new ToolResultTextDeltaEvent(replyId, toolId, MALFORMED_TOOL_PLACEHOLDER, errorMsg));
            syntheticEvents.add(new ToolResultEndEvent(replyId, toolId, MALFORMED_TOOL_PLACEHOLDER, ToolResultState.ERROR));

            // 2. 注入错误 ToolResultBlock 到 agent 上下文
            ToolResultBlock errorResult = ToolResultBlock.builder()
                    .id(toolId)
                    .name(MALFORMED_TOOL_PLACEHOLDER)
                    .output(List.of(TextBlock.builder().text(errorMsg).build()))
                    .state(ToolResultState.ERROR)
                    .build();

            // 用 ToolResultMessageBuilder 构建完整的 ToolResult Msg
            Msg errorResultMsg = ToolResultMessageBuilder.buildToolResultMsg(
                    errorResult, tub, agent.getName());
            state.contextMutable().add(errorResultMsg);
        }

        // --- 拼接：合成事件 + 正常事件 ---
        if (valid.isEmpty()) {
            // 所有 tool call 都畸形，只返回合成事件
            return Flux.fromIterable(syntheticEvents);
        }

        // 部分有效：先返回合成事件，再执行有效的 tool calls
        return Flux.concat(
                Flux.fromIterable(syntheticEvents),
                next.apply(new ActingInput(valid)));
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
