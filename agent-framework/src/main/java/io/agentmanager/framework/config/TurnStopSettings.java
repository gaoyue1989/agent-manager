package io.agentmanager.framework.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 单轮停止（POST /threads/{sessionId}/stop）运行参数。
 *
 * <p>独立小 bean 而非并入 {@link AgentManagerProperties}：后者是 record，新增组件
 * 需要同步改兼容构造器并波及全部按位构造测试（HistoryConfig javadoc 记载的同源问题）。
 *
 * <ul>
 *   <li>{@code AGENT_TURN_STOP_ENABLED}（默认 true）：总开关；关闭时端点返回 404、
 *       属主不轮询，行为完全回退到「只能断流」。</li>
 *   <li>{@code AGENT_TURN_STOP_POLL_MS}（默认 1000）：属主副本全局轮询周期。
 *       所有活跃 turn 共用一次查询，成本与并发 turn 数无关。</li>
 *   <li>{@code AGENT_TURN_STOP_GRACE_MS}（默认 15000）：协作式停止的告警阈值——
 *       超过仍无终态时打 WARN（通常是阻塞中的工具调用，SDK 检查点在工具返回后）。</li>
 * </ul>
 */
@Component
public class TurnStopSettings {

    private final boolean enabled;
    private final long pollIntervalMs;
    private final long graceMs;

    public TurnStopSettings(
            @Value("${agent.turn-stop.enabled:true}") boolean enabled,
            @Value("${agent.turn-stop.poll-interval-ms:1000}") long pollIntervalMs,
            @Value("${agent.turn-stop.grace-ms:15000}") long graceMs) {
        this.enabled = enabled;
        this.pollIntervalMs = Math.max(100, pollIntervalMs);
        this.graceMs = Math.max(0, graceMs);
    }

    public boolean enabled() {
        return enabled;
    }

    public long pollIntervalMs() {
        return pollIntervalMs;
    }

    public long graceMs() {
        return graceMs;
    }
}
