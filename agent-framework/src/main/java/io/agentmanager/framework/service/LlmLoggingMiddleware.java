package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

/**
 * LLM 调用记录中间件：通过 SDK 官方中间件扩展点 onModelCall 拦截每次模型调用，
 * 在 ModelCallEndEvent 发出时记录 请求(messages/tools) + 用量(usage) 到 LLMLogger，
 * 供 debug 页面 /debug/threads/{sessionId}/llm-calls 展示。
 */
public class LlmLoggingMiddleware implements MiddlewareBase {

    private final LLMLogger llmLogger;

    public LlmLoggingMiddleware(LLMLogger llmLogger) {
        this.llmLogger = llmLogger;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx,
            ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        var startNanos = System.nanoTime();
        return next.apply(input).doOnNext(evt -> {
            if (evt instanceof ModelCallEndEvent end) {
                var request = Map.<String, Object>of(
                    "model", input.model().getModelName(),
                    "messages", input.messages().stream()
                        .map(this::toSimpleMessage)
                        .toList(),
                    "tools", input.tools().stream()
                        .map(ToolSchema::getName)
                        .toList());
                var usage = end.getUsage();
                var response = Map.<String, Object>of(
                    "duration_ms", (System.nanoTime() - startNanos) / 1_000_000,
                    "usage", Map.of(
                        "input_tokens", usage != null ? usage.getInputTokens() : 0,
                        "output_tokens", usage != null ? usage.getOutputTokens() : 0,
                        "total_tokens", usage != null ? usage.getTotalTokens() : 0));
                llmLogger.logCall(resolveSessionKey(ctx), request, response);
            }
        });
    }

    /** 会话标识：优先完整 sessionId（与 /debug/threads 列表一致），缺失时回退 userId */
    private static String resolveSessionKey(RuntimeContext ctx) {
        var sid = ctx.getSessionId();
        if (sid != null && !sid.isBlank()) {
            return sid;
        }
        var uid = ctx.getUserId();
        return uid != null && !uid.isBlank() ? uid : "global";
    }

    /** Msg → {role, content} 简化结构，供前端弹窗直接渲染 */
    private Map<String, Object> toSimpleMessage(Msg m) {
        var text = new StringBuilder();
        if (m.getContent() != null) {
            for (var block : m.getContent()) {
                if (block instanceof TextBlock tb) {
                    text.append(tb.getText());
                }
            }
        }
        return Map.of(
            "role", String.valueOf(m.getRole()),
            "content", text.toString());
    }
}
