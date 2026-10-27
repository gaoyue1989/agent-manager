package io.agentmanager.framework.service;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

/**
 * 停止信号投递路由：把本会话**生效的** {@code (agent 实例, userId, sessionId)} 登记进
 * {@link TurnControlRegistry}，供属主副本的停止轮询调用 SDK 的按会话 interrupt。
 *
 * <p>为什么必须在这里取键：Channel 链路（/threads/chat 经 ChatUiChannel 网关）下 SDK 会
 * 改写 RuntimeContext——{@code userId} 是网关 peer（= 前端 sessionId）、{@code sessionId}
 * 是网关 gw-hash，与业务 sid 不同（见 {@link SessionKeyResolver} 类注释）。onAgent 是
 * 能看到改写后 ctx 的稳定扩展点，避免按前端 sid 猜测导致误杀其他会话。
 *
 * <p>登记是覆盖式：每次 agent 调用（含 HITL 恢复、子 agent 调用）刷新为当前调用实例；
 * 陈旧条目对 interrupt 无害（SDK 在新 call 开始时 reset 中断标志）。
 */
public class TurnInterruptMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(TurnInterruptMiddleware.class);

    private final TurnControlRegistry registry;
    private final SessionKeyResolver sessionKeyResolver;

    public TurnInterruptMiddleware(TurnControlRegistry registry,
                                   SessionKeyResolver sessionKeyResolver) {
        this.registry = registry;
        this.sessionKeyResolver = sessionKeyResolver;
    }

    /** 最外层（更大 = 更外层）：先登记再进入任何可能短路的中间件 */
    @Override
    public int order() {
        return 3;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (registry != null && ctx != null) {
            try {
                var canonical = sessionKeyResolver != null
                    ? sessionKeyResolver.canonicalSessionId(ctx)
                    : ctx.getSessionId();
                registry.register(canonical, agent, ctx.getUserId(), ctx.getSessionId());
            } catch (Exception e) {
                // 路由登记失败只影响「本 Pod 能否投递停止」，绝不阻断对话主链路
                log.warn("TurnInterruptMiddleware: register failed: {}", e.getMessage());
            }
        }
        return next.apply(input);
    }
}
