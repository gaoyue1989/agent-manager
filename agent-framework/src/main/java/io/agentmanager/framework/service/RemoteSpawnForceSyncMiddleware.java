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
 * 远程 spawn 强制同步等待中间件（travel-fulfillment-agent-protocol-design §16 实测发现）。
 *
 * <p><b>问题</b>（order-fulfillment demo 实证）：SDK 2.0.3 对<b>远程</b>子 agent 的
 * {@code agent_spawn} 恒走异步受理（F15：即使 timeout_seconds=60 也立即返回
 * {@code status: accepted}），子任务结果只能靠模型自觉调
 * {@code task_output}/{@code wait_async_results} 收割——收割工具返回的是快照
 * （Running / 空），真实 LLM 面对它反复放弃，多 agent 编排结果回流不可靠。
 *
 * <p><b>机制</b>：SDK AgentSpawnTool 在 spawn 时读取 RuntimeContext 属性
 * {@code agentscope.subagent.force_sync}（boolean）与
 * {@code agentscope.subagent.force_sync_timeout_seconds}（int，字节码实证）——
 * force_sync 为 true 时 spawn 阻塞等待子任务完成（受 timeout 上限约束），
 * 完成即以子任务真实结果返回。本中间件在<b>纯 spawn 轮次</b>（与
 * {@link RemoteUserIdMiddleware} 同一判定，混编/ask 轮次保守跳过）：
 * <ol>
 *   <li><b>改写 spawn 入参</b>：timeout_seconds 缺失/为 0 时强制为 waitSeconds
 *       （demo 实证真实模型常传 0=fire-and-forget，而远程 spawn 传正数时 SDK 会阻塞
 *       到子任务完成并内联返回结果；0 使同步语义整体失效）；</li>
 *   <li>注入上述两个 ctx 属性（双保险）。</li>
 * </ol>
 * Flux 终止时恢复属性原值，把"后台受理 + 模型自觉收割"转化为"阻塞等待 + 结果确定性回流"。
 *
 * <p>开关：{@code agent.agent-protocol.remote-spawn-sync-wait}（默认 true，
 * env AGENT_REMOTE_SPAWN_SYNC_WAIT）；等待秒数
 * {@code remote-spawn-sync-wait-seconds}（默认 120，env AGENT_REMOTE_SPAWN_SYNC_WAIT_SECONDS）。
 * 仅影响声明了远程子 agent 的 lead 服务；无远程声明的服务属性注入无消费方，无副作用。
 */
public class RemoteSpawnForceSyncMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(RemoteSpawnForceSyncMiddleware.class);

    /** SDK AgentSpawnTool 读取的 RuntimeContext 属性键（2.0.3 字节码实证） */
    public static final String CTX_FORCE_SYNC = "agentscope.subagent.force_sync";
    public static final String CTX_FORCE_SYNC_TIMEOUT_SECONDS = "agentscope.subagent.force_sync_timeout_seconds";

    private final boolean enabled;
    private final int waitSeconds;

    public RemoteSpawnForceSyncMiddleware(boolean enabled, int waitSeconds) {
        this.enabled = enabled;
        this.waitSeconds = waitSeconds > 0 ? waitSeconds : 120;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (!enabled || ctx == null || !RemoteUserIdMiddleware.isPureSpawnRound(input)) {
            return next.apply(input);
        }
        // 改写入参：timeout_seconds 缺失/为 0 时强制为 waitSeconds——demo 实证（§16）模型
        // 常传 0（fire-and-forget），而 SDK 远程 spawn 传正数时会阻塞等子任务完成并内联
        // 返回结果（resolveEffectiveTimeoutMs 取 min(参数, force_sync 属性)），0 使一切
        // 同步语义失效、收割全靠模型自觉
        var rewritten = new java.util.ArrayList<ToolUseBlock>();
        boolean changed = false;
        for (var tc : input.toolCalls()) {
            var in = tc.getInput();
            var timeout = in != null ? in.get("timeout_seconds") : null;
            int value = timeout instanceof Number n ? n.intValue()
                : timeout instanceof String s && s.matches("\\d+") ? Integer.parseInt(s) : -1;
            if (value > 0) {
                rewritten.add(tc);
                continue;
            }
            var fixed = new java.util.LinkedHashMap<String, Object>();
            if (in != null) {
                fixed.putAll(in);
            }
            fixed.put("timeout_seconds", waitSeconds);
            rewritten.add(ToolUseBlock.builder().id(tc.getId()).name(tc.getName())
                .input(java.util.Collections.unmodifiableMap(fixed)).build());
            changed = true;
            log.info("[remote-spawn-sync] spawn timeout_seconds {} -> {} (agent_id={})",
                timeout, waitSeconds, fixed.get("agent_id"));
        }
        Object prevSync = ctx.get(CTX_FORCE_SYNC);
        Object prevSeconds = ctx.get(CTX_FORCE_SYNC_TIMEOUT_SECONDS);
        ctx.put(CTX_FORCE_SYNC, Boolean.TRUE);
        ctx.put(CTX_FORCE_SYNC_TIMEOUT_SECONDS, waitSeconds);
        log.info("[remote-spawn-sync] force_sync=true, waitSeconds={} injected for spawn round", waitSeconds);
        var out = changed ? new ActingInput(rewritten) : input;
        return next.apply(out).doFinally(sig -> restore(ctx, prevSync, prevSeconds));
    }

    /** 恢复进入前的属性值（原先缺失则不回写 null，避免 SDK 对 null 属性的未知假设） */
    private void restore(RuntimeContext ctx, Object prevSync, Object prevSeconds) {
        try {
            if (prevSync != null) {
                ctx.put(CTX_FORCE_SYNC, prevSync);
            }
            if (prevSeconds != null) {
                ctx.put(CTX_FORCE_SYNC_TIMEOUT_SECONDS, prevSeconds);
            }
        } catch (Exception e) {
            log.warn("[remote-spawn-sync] attribute restore failed: {}", e.getMessage());
        }
    }
}
