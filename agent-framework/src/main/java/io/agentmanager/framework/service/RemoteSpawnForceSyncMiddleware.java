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
 * <p>作用域收敛（修评审 #62 确认缺陷）：只对<b>远程声明</b>（OAF subAgents 中 endpoint
 * 非空）的 spawn 目标改写/注入——本地子 agent 的 fire-and-forget（timeout_seconds=0）与
 * 缺省 30s 同步窗口语义不因本中间件改变。整轮全部目标均远程声明才注入 ctx 属性
 * force_sync 属性是轮级全局，混编远程/本地 spawn 的轮次注入会连带改写本地 spawn——
 * 此时只做远程调用的逐参改写（timeout_seconds>0 已驱动 SDK 阻塞同步路径，功能不回退），
 * 保守放弃 force_sync 的超时升格/无视传参两点增量。无任何远程声明目标的一轮零改写零
 * 注入（契约：无远程声明 → 零行为变化）。
 *
 * <p>开关：{@code agent.agent-protocol.remote-spawn-sync-wait}（默认 true，
 * env AGENT_REMOTE_SPAWN_SYNC_WAIT）；等待秒数
 * {@code remote-spawn-sync-wait-seconds}（默认 120，env AGENT_REMOTE_SPAWN_SYNC_WAIT_SECONDS）。
 */
public class RemoteSpawnForceSyncMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(RemoteSpawnForceSyncMiddleware.class);

    /** SDK AgentSpawnTool 读取的 RuntimeContext 属性键（2.0.3 字节码实证） */
    public static final String CTX_FORCE_SYNC = "agentscope.subagent.force_sync";
    public static final String CTX_FORCE_SYNC_TIMEOUT_SECONDS = "agentscope.subagent.force_sync_timeout_seconds";

    private final boolean enabled;
    private final int waitSeconds;
    /** 远程声明 spawn 目标名集（OAF subAgents 中 endpoint 非空者）；空集 = 无远程声明，整体惰性 */
    private final java.util.Set<String> remoteAgentNames;

    /** 兼容构造：无远程声明集 = 整体惰性（零改写零注入） */
    public RemoteSpawnForceSyncMiddleware(boolean enabled, int waitSeconds) {
        this(enabled, waitSeconds, java.util.Set.of());
    }

    public RemoteSpawnForceSyncMiddleware(boolean enabled, int waitSeconds,
                                          java.util.Set<String> remoteAgentNames) {
        this.enabled = enabled;
        this.waitSeconds = waitSeconds > 0 ? waitSeconds : 120;
        this.remoteAgentNames = remoteAgentNames == null
            ? java.util.Set.of() : java.util.Set.copyOf(remoteAgentNames);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (!enabled || ctx == null || !RemoteUserIdMiddleware.isPureSpawnRound(input)) {
            return next.apply(input);
        }
        // 逐调用作用域判定（修 #62）：本地声明的目标原样透传——timeout 0 = fire-and-forget、
        // 缺省 = SDK 默认同步窗口，均不因本中间件改变
        var rewritten = new java.util.ArrayList<ToolUseBlock>();
        boolean changed = false;
        boolean anyRemote = false;
        boolean allRemote = true;
        for (var tc : input.toolCalls()) {
            var in = tc.getInput();
            var target = in != null ? in.get("agent_id") : null;
            boolean remote = target instanceof String s && !s.isBlank() && remoteAgentNames.contains(s);
            if (!remote) {
                allRemote = false;
                rewritten.add(tc);
                continue;
            }
            anyRemote = true;
            // 改写入参：timeout_seconds 缺失/为 0 时强制为 waitSeconds——demo 实证（§16）模型
            // 常传 0（fire-and-forget），而 SDK 远程 spawn 传正数时会阻塞等子任务完成并内联
            // 返回结果（resolveEffectiveTimeoutMs 取 min(参数, force_sync 属性)），0 使一切
            // 同步语义失效、收割全靠模型自觉
            var timeout = in != null ? in.get("timeout_seconds") : null;
            int value = timeout instanceof Number n ? n.intValue()
                : timeout instanceof String st && st.matches("\\d+") ? Integer.parseInt(st) : -1;
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
        if (!anyRemote) {
            // 整轮无远程声明目标：零改写零注入（无远程声明的服务 → 零行为变化）
            return next.apply(input);
        }
        var out = changed ? new ActingInput(rewritten) : input;
        if (!allRemote) {
            // 混编轮次（远程 + 本地 spawn）：force_sync 属性是轮级全局，注入会连带改写
            // 本地 spawn 语义——保守只保留逐参改写。远程调用 timeout_seconds>0 已驱动
            // SDK 阻塞同步路径（超时升格后台），功能不回退，仅失去超时升格禁用/
            // 无视模型传参两点 force_sync 增量
            log.info("[remote-spawn-sync] mixed spawn round (remote+local targets), "
                + "force_sync injection skipped, per-call rewrite only (remote call stays "
                + "on SDK sync path via positive timeout)");
            return next.apply(out);
        }
        Object prevSync = ctx.get(CTX_FORCE_SYNC);
        Object prevSeconds = ctx.get(CTX_FORCE_SYNC_TIMEOUT_SECONDS);
        ctx.put(CTX_FORCE_SYNC, Boolean.TRUE);
        ctx.put(CTX_FORCE_SYNC_TIMEOUT_SECONDS, waitSeconds);
        log.info("[remote-spawn-sync] force_sync=true, waitSeconds={} injected for spawn round", waitSeconds);
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
