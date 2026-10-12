package io.agentmanager.framework.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

/**
 * Model 装饰器：主模型首个信号即失败时切换到备用模型（SDK {@code modelForCall} 等价语义）。
 *
 * <h3>背景</h3>
 * SDK 的备用模型能力（{@code ModelConfig.fallbackModel} + {@code ReActAgent.modelForCall()}）
 * 只包裹 agent 的默认模型 {@code this.model}；本工程的会话模型切换在
 * {@link SessionModelMiddleware} 里替换 {@code ModelCallInput.model()}，会绕过 SDK 的 fallback
 * 包装。本类在会话模型路径上补齐同一语义，使「默认模型 + 会话自选模型」都有备用。
 *
 * <h3>触发条件</h3>
 * 与 SDK 一致：仅当主模型返回的**首个信号**是 error 时才切换。
 * 单次模型调用内部的重试（429/5xx/超时/网络）由 {@code ExecutionConfig} 先耗尽，
 * 因此切换发生在重试预算耗尽之后；已产出增量后再失败不会切换（避免重复输出）。
 */
public class FallbackModelWrapper implements Model {

    private static final Logger log = LoggerFactory.getLogger(FallbackModelWrapper.class);

    private final Model primary;
    private final Model fallback;

    public FallbackModelWrapper(Model primary, Model fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /** 备用模型为空时直接返回主模型，避免无意义的包装。 */
    public static Model wrap(Model primary, Model fallback) {
        return fallback == null ? primary : new FallbackModelWrapper(primary, fallback);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        return primary.stream(messages, tools, options)
            .switchOnFirst((signal, flux) -> {
                if (signal.isOnError()) {
                    Throwable error = signal.getThrowable();
                    log.warn("Primary model '{}' failed ({}), switching to fallback model '{}'",
                        primary.getModelName(), error != null ? error.getMessage() : "unknown",
                        fallback.getModelName(), error);
                    return fallback.stream(messages, tools, options);
                }
                return flux;
            });
    }

    // ---- 委托主模型的静态元信息（模型名/能力），保持下游 span、日志口径一致 ----

    @Override
    public String getModelName() {
        return primary.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return primary.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return primary.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return primary.getContextWindowSize();
    }
}
