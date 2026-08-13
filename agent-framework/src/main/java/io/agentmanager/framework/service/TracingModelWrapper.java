package io.agentmanager.framework.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadableSpan;
import reactor.core.publisher.Flux;

/**
 * Model 装饰器：为绕过 middleware 链的 LLM 调用创建 OTel span。
 *
 * <p>用于 memory flush/consolidation（MemoryFlushManager/MemoryConsolidator）
 * 与 compaction（CompactionMiddleware）持有的 model——这三处直接调用
 * model.stream()，不经过 onModelCall 链，OtelTracingMiddleware 无法覆盖。
 *
 * <p>父级：调用发生在 middleware 链的 runWithContext 作用域内（memory flush
 * 在 onAgent 链、compaction 在 onReasoning 链），Context.current() 为
 * invoke_agent 或 reasoning span，子 span 归属同一 trace。若 SDK 内部为
 * 后台异步调用导致父级丢失，降级为根 span（需实测，见验证步骤）。
 */
public class TracingModelWrapper implements Model {

    private static final String INSTRUMENTATION_NAME = "io.agentmanager.framework";

    private static final List<String> TENANT_ATTRIBUTE_KEYS = List.of(
            "agentscope.user.id", "agentscope.session.id", "agentscope.tenant.prefix");

    private final Model delegate;
    private final String spanName;   // "memory" / "compaction"

    public TracingModelWrapper(Model delegate, String spanName) {
        this.delegate = delegate;
        this.spanName = spanName;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        return Flux.defer(() -> {
            Context parent = Context.current();
            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME)
                    .spanBuilder(spanName)
                    .setParent(parent)
                    .setAttribute("gen_ai.operation.name", spanName)
                    .setAttribute("gen_ai.request.model", delegate.getModelName())
                    .setAttribute("gen_ai.request.messages.count",
                            messages != null ? (long) messages.size() : 0L)
                    .startSpan();

            // 从父 span 复制租户属性，保证按会话/用户过滤时命中（中间件链的
            // FrameworkTracingMiddleware 不会覆盖本 span）
            copyTenantAttributes(Span.fromContext(parent), span);

            AtomicReference<Boolean> ended = new AtomicReference<>(false);
            return delegate.stream(messages, tools, options)
                    .doOnNext(resp -> {
                        if (resp.getUsage() != null) {
                            span.setAttribute("gen_ai.usage.input_tokens",
                                    (long) resp.getUsage().getInputTokens());
                            span.setAttribute("gen_ai.usage.output_tokens",
                                    (long) resp.getUsage().getOutputTokens());
                        }
                    })
                    .doOnComplete(() -> {
                        if (ended.compareAndSet(false, true)) {
                            span.setStatus(StatusCode.OK);
                            span.end();
                        }
                    })
                    .doOnError(e -> {
                        if (ended.compareAndSet(false, true)) {
                            span.setStatus(StatusCode.ERROR, e.getMessage());
                            span.recordException(e);
                            span.end();
                        }
                    })
                    .doOnCancel(() -> {
                        if (ended.compareAndSet(false, true)) {
                            span.end();
                        }
                    });
        });
    }

    private void copyTenantAttributes(Span parentSpan, Span span) {
        // Span API 1.61.0 无 getAttribute；仅在父 span 为 SDK 实现（ReadableSpan）时读取。
        // no-op tracer（OTEL_TRACES_EXPORTER=none）返回的 span 非 ReadableSpan，跳过复制（本也无 trace）。
        if (!(parentSpan instanceof ReadableSpan readable)) {
            return;
        }
        for (String key : TENANT_ATTRIBUTE_KEYS) {
            String value = readable.getAttribute(AttributeKey.stringKey(key));
            if (value != null && !value.isBlank()) {
                span.setAttribute(key, value);
            }
        }
    }

    // ---- 委托方法 ----

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}