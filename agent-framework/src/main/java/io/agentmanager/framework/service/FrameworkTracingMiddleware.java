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
 *
 * <p><b>属性值是业务规范键，不是 RuntimeContext 原值</b>（见 {@link SessionKeyResolver}）：
 * Channel 链路的 ctx.sessionId 是全进程共享的网关 gw-hash、ctx.userId 是网关 peer，
 * 原样记录会让 agentscope.session.id 全进程同值（按会话过滤失效、各会话 span 互相可见）、
 * agentscope.user.id 变成会话 id 而非用户（与 MCP 侧 McpMeta.userId 口径也不一致）。
 * 解析在每个 hook 入口做一次（结果 memo 在 RuntimeContext，生命周期 = 一个 turn），
 * 绝不放进 doOnNext——那会在事件级热路径上反复解析。
 */
public class FrameworkTracingMiddleware implements MiddlewareBase {

    private final String tenantPrefix;
    private final SessionKeyResolver sessionKeyResolver;

    /** 无规范键解析（单测 / 极简装配）：退化为 RuntimeContext 原值 */
    public FrameworkTracingMiddleware(String tenantPrefix) {
        this(tenantPrefix, null);
    }

    public FrameworkTracingMiddleware(String tenantPrefix, SessionKeyResolver sessionKeyResolver) {
        this.tenantPrefix = tenantPrefix;
        this.sessionKeyResolver = sessionKeyResolver;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        var ids = resolveIds(ctx);
        return next.apply(input).doOnNext(event -> enrichCurrentSpan(ids));
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        var ids = resolveIds(ctx);
        return next.apply(input).doOnNext(event -> enrichCurrentSpan(ids));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        var ids = resolveIds(ctx);
        return next.apply(input).doOnNext(event -> enrichCurrentSpan(ids));
    }

    /** 规范会话 id / 真实用户 id（无 resolver 时取 RuntimeContext 原值） */
    private SessionIds resolveIds(RuntimeContext ctx) {
        if (sessionKeyResolver == null || ctx == null) {
            return new SessionIds(ctx == null ? null : ctx.getSessionId(),
                ctx == null ? null : ctx.getUserId());
        }
        return new SessionIds(sessionKeyResolver.canonicalSessionId(ctx),
            sessionKeyResolver.realUserId(ctx));
    }

    /**
     * 向当前活跃 span 写入业务属性。
     * 在 doOnNext 中执行时，Otel 的 ContextPropagationOperator.runWithContext()
     * 已生效，Span.current() 指向 Otel 创建的 span（invoke_agent / chat / execute_tool）。
     */
    private void enrichCurrentSpan(SessionIds ids) {
        Span span = Span.current();
        if (ids.userId() != null && !ids.userId().isBlank()) {
            span.setAttribute("agentscope.user.id", ids.userId());
        }
        if (ids.sessionId() != null && !ids.sessionId().isBlank()) {
            span.setAttribute("agentscope.session.id", ids.sessionId());
        }
        if (tenantPrefix != null && !tenantPrefix.isBlank()) {
            span.setAttribute("agentscope.tenant.prefix", tenantPrefix);
        }
    }

    /** 一次 hook 内固定的业务标识（避免每个事件重复解析） */
    private record SessionIds(String sessionId, String userId) {
    }
}
