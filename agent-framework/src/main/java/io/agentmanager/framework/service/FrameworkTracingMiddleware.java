package io.agentmanager.framework.service;

import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.opentelemetry.api.trace.Span;
import reactor.core.publisher.Flux;

/**
 * 框架级链路追踪中间件：补充 agent-framework 特有业务属性到 OTel span。
 *
 * <p>依赖 OtelTracingMiddleware 已创建的 span：OtelTracingMiddleware 通过
 * ContextPropagationOperator.runWithContext() 将 OTel Context 注入 Reactor Context，
 * 本中间件在 next.apply() 内部执行时 Span.current() 即可读取到 Otel 创建的 span。
 *
 * <p>执行顺序由注册顺序决定（MiddlewareChain 按注册序构建，先注册者更外层），
 * 需在 RegisterAgent 时先注册 OtelTracingMiddleware 再注册本中间件（AgentScopeConfig）。
 *
 * <p>覆盖 onAgent/onModelCall/onActing 三个钩子，保证 userId/sessionId/tenantPrefix
 * 出现在所有 span（invoke_agent/chat/execute_tool）上，而非仅根 span，
 * 使 Jaeger 按会话/用户过滤时子 span 可命中。
 */
public class FrameworkTracingMiddleware implements MiddlewareBase {

    private final String tenantPrefix;

    public FrameworkTracingMiddleware(String tenantPrefix) {
        this.tenantPrefix = tenantPrefix;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(event -> enrichCurrentSpan(ctx));
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(event -> enrichCurrentSpan(ctx));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(event -> enrichCurrentSpan(ctx));
    }

    /**
     * 向当前活跃 span 写入业务属性。
     * 在 doOnNext 中执行时，Otel 的 ContextPropagationOperator.runWithContext()
     * 已生效，Span.current() 指向 Otel 创建的 span（invoke_agent / chat / execute_tool）。
     */
    private void enrichCurrentSpan(RuntimeContext ctx) {
        Span span = Span.current();
        if (ctx != null) {
            if (ctx.getUserId() != null && !ctx.getUserId().isBlank()) {
                span.setAttribute("agentscope.user.id", ctx.getUserId());
            }
            if (ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
                span.setAttribute("agentscope.session.id", ctx.getSessionId());
            }
        }
        if (tenantPrefix != null && !tenantPrefix.isBlank()) {
            span.setAttribute("agentscope.tenant.prefix", tenantPrefix);
        }
    }
}