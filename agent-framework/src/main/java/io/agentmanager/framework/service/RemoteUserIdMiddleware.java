package io.agentmanager.framework.service;

import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

/**
 * 远程子 agent 身份规范化中间件（travel-fulfillment-agent-protocol-design §8 lead-4，事实 F10）。
 *
 * <p>把 Channel 链路的网关 peer/gw-hash userId 经 session_user 反查为规范 userId，使
 * Harness 内置 AgentSpawnTool 组装 RemoteSubmitContext 时拿到真实用户而非会话 id——
 * agent_spawn 远程提交把 {@code ctx.getUserId()} 原样序列化为 {@code context.user_id}
 * 下传子服务（AgentSpawnTool.buildRemoteSubmitContext 字节码直读 getUserId()，无 ctx
 * 属性旁路），Channel 链路该值是网关 peer（=前端 sid），子服务按它做 USER 隔离会整桶错位。
 *
 * <p><b>写回必须作用域化（CI 回归教训，PR #62 门禁）</b>：最初实现挂在 onAgent 全程改写
 * ctx.userId，实测破坏 SDK HITL 挂起/恢复——SDK 内部同样读取 ctx.userId 参与暂停态
 * 记账（e2e 实证：permission_ask 后 confirm-stream 恢复失败，agent 停留 paused 态并级联
 * 污染后续所有会话），"写回只影响 spawn 消费方"的设计假设不成立。现改为 <b>onActing
 * 作用域写回</b>：仅当本轮 acting 的工具调用全部是 {@code agent_spawn} 时，进入前写回
 * 规范 userId、Flux 终止时（doFinally）恢复原值——HITL 挂起只发生在工具级 ask 轮次，
 * 与 spawn 轮次互斥，挂起/恢复路径看到的始终是原始 userId；混编轮次（spawn 与其他工具
 * 同轮）保守跳过，不追求覆盖。
 *
 * <p>解析复用 {@link SessionKeyResolver}（turn 级 memo，与 McpUserContextMiddleware
 * 同源逻辑）：A2A/直调链路 userId 已是真实用户（反查未命中回落原值），无变化不写。
 * <b>只写回 userId、不写回 sessionId</b>（设计 §5.6 决策：SDK 对 sessionId 的内部假设
 * 不明，沿用 SessionKeyResolver 后期翻译模式）。
 */
public class RemoteUserIdMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(RemoteUserIdMiddleware.class);

    /** RuntimeContext.userId 字段句柄（类加载期解析一次；不可达时保持 null 并降级为直通） */
    private static final java.lang.reflect.Field USER_ID_FIELD = initUserIdField();

    private final SessionKeyResolver sessionKeyResolver;

    public RemoteUserIdMiddleware(SessionUserStore sessionUserStore) {
        this(new SessionKeyResolver(sessionUserStore));
    }

    /** 直接注入 resolver（单测用） */
    RemoteUserIdMiddleware(SessionKeyResolver sessionKeyResolver) {
        this.sessionKeyResolver = sessionKeyResolver;
    }

    private static java.lang.reflect.Field initUserIdField() {
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
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (ctx == null || USER_ID_FIELD == null || !isPureSpawnRound(input)) {
            return next.apply(input);
        }
        var original = ctx.getUserId();
        if (original == null || original.isBlank()) {
            return next.apply(input);
        }
        var resolved = sessionKeyResolver.realUserId(ctx);
        if (resolved == null || resolved.isBlank() || resolved.equals(original)) {
            return next.apply(input);
        }
        try {
            USER_ID_FIELD.set(ctx, resolved);
        } catch (Exception e) {
            log.warn("[remote-user] canonical userId write-back failed for '{}': {}", original, e.getMessage());
            return next.apply(input);
        }
        log.info("[remote-user] canonical userId scoped for spawn round: {} -> {}", original, resolved);
        // Flux 终止（含取消/错误）即恢复原值：spawn 轮次不含 ask 工具，正常在工具结果
        // 返回后终止；恢复保证 HITL 挂起/恢复等后续 SDK 记账看到的仍是原始身份
        return next.apply(input).doFinally(sig -> restore(ctx, original, resolved));
    }

    /** 本轮 acting 是否为纯 agent_spawn 轮次（非空且全部为 spawn；混编轮次保守跳过） */
    static boolean isPureSpawnRound(ActingInput input) {
        List<ToolUseBlock> calls = input != null ? input.toolCalls() : null;
        if (calls == null || calls.isEmpty()) {
            return false;
        }
        return calls.stream().allMatch(tc -> "agent_spawn".equals(tc.getName()));
    }

    /** 恢复原始 userId（fail-soft：恢复失败仅告警，下一轮 acting 会重新写回） */
    private void restore(RuntimeContext ctx, String original, String rewritten) {
        try {
            USER_ID_FIELD.set(ctx, original);
        } catch (Exception e) {
            log.warn("[remote-user] userId restore failed ({} -> {}): {}", rewritten, original, e.getMessage());
        }
    }
}
