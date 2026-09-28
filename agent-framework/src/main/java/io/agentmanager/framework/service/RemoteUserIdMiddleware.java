package io.agentmanager.framework.service;

import java.lang.reflect.Field;
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
 * 远程子 agent 身份规范化中间件（travel-fulfillment-agent-protocol-design §8 lead-4，事实 F10）。
 *
 * <p>把 Channel 链路的网关 peer/gw-hash userId 经 session_user 反查为规范 userId 写回
 * RuntimeContext，使 Harness 内置 AgentSpawnTool 组装 RemoteSubmitContext 时拿到真实
 * 用户而非会话 id——agent_spawn 远程提交把 {@code ctx.getUserId()} 原样序列化为
 * {@code context.user_id} 下传子服务（AgentSpawnTool.buildRemoteSubmitContext 字节码
 * 直读 getUserId()，无 ctx 属性旁路），Channel 链路该值是网关 peer（=前端 sid），
 * 子服务按它做 USER 隔离会整桶错位。
 *
 * <p>写回机制（SDK 2.0.3 实证，javap + JDK 21 实测）：{@code RuntimeContext.userId}
 * 是 private final 字段、无 setter；中间件各钩子（onAgent/onActing）只能透传同一 ctx
 * 实例（AgentInput 仅承载消息列表，无法换 ctx）。工具执行看到的正是本中间件 onAgent
 * 收到的同一实例（先例：{@code McpUserContextMiddleware} 在 onAgent 写入 McpMeta，
 * 工具调用期可见）。因此写回 = 反射写 final 实例字段——对普通类（非 record/hidden
 * class）属 Field.set 契约允许的操作；SDK 升级若改为 record/改字段名，本中间件按
 * fail-soft 降级（warn + 保留原值），不阻断对话。
 *
 * <p>解析复用 {@link SessionKeyResolver}（turn 级 memo，与 McpUserContextMiddleware
 * 同源逻辑）：先解析、后写回，memo 记录的是写回前的规范键，下游（LlmLogging、
 * 追踪 span、RemoteSpawnCaptureMiddleware 的规范 sid 翻译）口径不变；A2A/直调
 * 链路 userId 已是真实用户（反查未命中回落原值），无变化不写。挂载点在
 * McpUserContextMiddleware 之后（后者按 peer 直接反查写 McpMeta 的行为保持现状），
 * 工具执行之前——写回只影响其后读取 ctx.getUserId() 的消费方。
 *
 * <p><b>只写回 userId、不写回 sessionId</b>（设计 §5.6 决策：SDK 对 sessionId 的
 * 内部假设不明，沿用 SessionKeyResolver 后期翻译模式；跨服务会话关联由
 * RemoteConfirmBridge 的 remote_task 表承担）。
 */
public class RemoteUserIdMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(RemoteUserIdMiddleware.class);

    /** RuntimeContext.userId 字段句柄（类加载期解析一次；不可达时保持 null 并降级为直通） */
    private static final Field USER_ID_FIELD = initUserIdField();

    private final SessionKeyResolver sessionKeyResolver;

    public RemoteUserIdMiddleware(SessionUserStore sessionUserStore) {
        this(new SessionKeyResolver(sessionUserStore));
    }

    /** 直接注入 resolver（单测用） */
    RemoteUserIdMiddleware(SessionKeyResolver sessionKeyResolver) {
        this.sessionKeyResolver = sessionKeyResolver;
    }

    private static Field initUserIdField() {
        try {
            var field = RuntimeContext.class.getDeclaredField("userId");
            field.setAccessible(true);
            return field;
        } catch (Exception e) {
            log.warn("[remote-user] RuntimeContext.userId field inaccessible, remote spawn will "
                + "carry gateway peer as context.user_id (SDK upgrade drift?): {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        writeBackCanonicalUserId(ctx);
        return next.apply(input);
    }

    /** 把 session_user 反查出的规范 userId 写回 ctx（无变化/不可写时静默直通） */
    private void writeBackCanonicalUserId(RuntimeContext ctx) {
        if (ctx == null || USER_ID_FIELD == null) {
            return;
        }
        var raw = ctx.getUserId();
        if (raw == null || raw.isBlank()) {
            return;
        }
        var resolved = sessionKeyResolver.realUserId(ctx);
        if (resolved == null || resolved.isBlank() || resolved.equals(raw)) {
            return;
        }
        try {
            USER_ID_FIELD.set(ctx, resolved);
            log.info("[remote-user] canonical userId written back: {} -> {}", raw, resolved);
        } catch (Exception e) {
            log.warn("[remote-user] canonical userId write-back failed for '{}': {}", raw, e.getMessage());
        }
    }
}
