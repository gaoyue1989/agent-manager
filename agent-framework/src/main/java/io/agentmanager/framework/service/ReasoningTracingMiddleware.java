package io.agentmanager.framework.service;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import reactor.core.publisher.Flux;

/**
 * ReAct 推理轮次追踪中间件：每轮 reasoning 创建独立 span。
 *
 * <p>OtelTracingMiddleware 未实现 onReasoning（默认直通），本中间件在其内层执行，
 * 父级为 invoke_agent span（由 Otel.onAgent 通过 ContextPropagationOperator.runWithContext
 * 注入 Reactor Context）。
 *
 * <p>执行顺序由注册顺序决定（先注册者更外层），需在注册 OtelTracingMiddleware
 * 之后注册本中间件（AgentScopeConfig 已保证）。
 *
 * <p>模型调用（onModelCall 链）在 reasoning 核心逻辑内执行，本中间件用
 * runWithContext 包裹 next.apply(input)，chat span 自动成为 reasoning span 的子级。
 */
public class ReasoningTracingMiddleware implements MiddlewareBase {

    private static final String INSTRUMENTATION_NAME = "io.agentmanager.framework";

    /** 惰性获取：middleware 构造可能早于 OtelConfig 注册 SDK，字段初始化会拿到永久 no-op tracer */
    private Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            Context parent = ContextPropagationOperator
                    .getOpenTelemetryContextFromContextView(ctxView, Context.current());
            Span span = tracer().spanBuilder("reasoning " + agent.getName())
                    .setParent(parent)
                    .setAttribute("gen_ai.operation.name", "reasoning")
                    .setAttribute("gen_ai.request.messages.count",
                            input.messages() != null ? (long) input.messages().size() : 0L)
                    .startSpan();
            Context otelCtx = span.storeInContext(parent);
            AtomicReference<Boolean> ended = new AtomicReference<>(false);

            return ContextPropagationOperator.runWithContext(
                    next.apply(input)
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
                                    span.setStatus(StatusCode.ERROR, "cancelled");
                                    span.end();
                                }
                            }),
                    otelCtx);
        });
    }
}