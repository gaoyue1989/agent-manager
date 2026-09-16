package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEventType;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import reactor.core.publisher.Mono;

/**
 * 检测思维模型"空完成"并自动恢复的 Hook。
 *
 * <p>背景：Qwen3 等思维模型在启用 thinking mode 时，reasoning_content（思考）和
 * content（实际输出）共享 {@code max_tokens} 预算。当思考消耗过多 token 时，
 * 模型可能只产出 ThinkingBlock 而没有 TextBlock 或 ToolUseBlock，
 * 导致 ReAct 的 {@code isFinished()} 判定为完成、循环提前终止，前端无任何输出。
 *
 * <p>本 Hook 在 {@code POST_REASONING} 阶段拦截：
 * <ul>
 *   <li>检测 reasoning message 是否只有 ThinkingBlock（无文本、无工具调用）</li>
 *   <li>若是，调用 {@code gotoReasoning()} 并注入一条用户消息引导模型继续输出</li>
 *   <li>设置最大恢复次数（默认 3），防止无限循环</li>
 * </ul>
 *
 * <p>注意：本 Hook 使用已被标记为 deprecated 的 Hook API，因为只有
 * {@code PostReasoningEvent.gotoReasoning()} 才能让 ReAct 循环继续；
 * Middleware 体系目前不提供等价能力。待框架 Middleware 支持 gotoReasoning 后可迁移。
 */
public class EmptyCompletionRecoveryHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(EmptyCompletionRecoveryHook.class);

    /** 最大连续恢复次数，防止无限循环 */
    private final int maxRecoveries;

    /** 当前连续恢复计数 */
    private int recoveryCount = 0;

    /** 注入给模型的继续提示 */
    private static final String CONTINUATION_PROMPT =
            "Your previous response contained only thinking/reasoning but no actual output. "
            + "Please provide your actual response now — either a text answer or a tool call. "
            + "Do not repeat the thinking process.";

    public EmptyCompletionRecoveryHook() {
        this(3);
    }

    public EmptyCompletionRecoveryHook(int maxRecoveries) {
        this.maxRecoveries = maxRecoveries;
    }

    @Override
    public int priority() {
        // 高优先级，确保在其他 PostReasoning Hook 之前执行
        return 50;
    }

    @Override
    public <T extends io.agentscope.core.hook.HookEvent> Mono<T> onEvent(T event) {
        if (event.getType() != HookEventType.POST_REASONING) {
            return Mono.just(event);
        }

        var postEvent = (PostReasoningEvent) event;
        Msg reasoningMsg = postEvent.getReasoningMessage();

        if (reasoningMsg == null) {
            // 没有消息也视为空完成
            return handleEmptyCompletion(postEvent, event, "null reasoning message");
        }

        boolean hasText = !reasoningMsg.getContentBlocks(TextBlock.class).isEmpty();
        boolean hasToolCalls = !reasoningMsg.getContentBlocks(ToolUseBlock.class).isEmpty();
        boolean hasThinking = !reasoningMsg.getContentBlocks(ThinkingBlock.class).isEmpty();

        if (!hasText && !hasToolCalls) {
            String detail = hasThinking
                    ? "only ThinkingBlock(s), no text or tool calls"
                    : "completely empty (no thinking, text, or tool calls)";
            return handleEmptyCompletion(postEvent, event, detail);
        }

        // 有实际输出，重置恢复计数
        if (recoveryCount > 0) {
            log.debug("[empty-completion-recovery] Reset recovery count (was {})", recoveryCount);
            recoveryCount = 0;
        }

        return Mono.just(event);
    }

    @SuppressWarnings("unchecked")
    private <T extends io.agentscope.core.hook.HookEvent> Mono<T> handleEmptyCompletion(
            PostReasoningEvent postEvent, T originalEvent, String detail) {

        if (recoveryCount >= maxRecoveries) {
            log.warn("[empty-completion-recovery] Max recoveries ({}) reached, giving up. Detail: {}",
                    maxRecoveries, detail);
            // 在消息中追加一条文本提示，至少让用户看到有内容
            Msg currentMsg = postEvent.getReasoningMessage();
            if (currentMsg != null) {
                String fallbackText = "[思考内容较长，响应被截断。请尝试缩短问题或增加 max_tokens 配置。]";
                List<ContentBlock> patchedContent = new ArrayList<>(currentMsg.getContent());
                patchedContent.add(TextBlock.builder().text(fallbackText).build());
                postEvent.setReasoningMessage(currentMsg.withContent(patchedContent));
            }
            return (Mono<T>) Mono.just(postEvent);
        }

        recoveryCount++;
        log.info("[empty-completion-recovery] Empty completion detected ({}), "
                + "requesting gotoReasoning (attempt {}/{})",
                detail, recoveryCount, maxRecoveries);

        // 注入一条用户消息引导模型继续
        Msg continuationMsg = UserMessage.builder()
                .content(TextBlock.builder().text(CONTINUATION_PROMPT).build())
                .build();
        postEvent.gotoReasoning(continuationMsg);

        return (Mono<T>) Mono.just(postEvent);
    }

    /**
     * 重置恢复计数（供外部调用，如新会话开始时）。
     */
    public void resetRecoveryCount() {
        this.recoveryCount = 0;
    }

    int getRecoveryCount() {
        return recoveryCount;
    }
}
