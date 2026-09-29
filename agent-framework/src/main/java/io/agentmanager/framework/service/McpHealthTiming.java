package io.agentmanager.framework.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP 看门狗调度参数（env 直传，不经 AgentManagerProperties——兼容构造器按位稳定不扩参）。
 *
 * <p>独立小 bean 的原因：{@code @Scheduled} 的 SpEL 不能自引用宿主 bean——
 * ScheduledAnnotationBeanPostProcessor 在 mcpConnectionWatchdog 自身创建期解析
 * {@code #{@mcpConnectionWatchdog...}}，此时 bean 尚未注册进容器，表达式解析失败
 * （PR #66 E2E 打包环境实证）。看门狗构造注入本 bean，保证解析时序。
 *
 * <p>配置：{@code AGENT_MCP_HEALTH_INTERVAL_SECONDS} 探测周期秒（默认 30，≤0 关闭）、
 * {@code AGENT_MCP_HEALTH_TIMEOUT_SECONDS} 单次探活超时秒（默认 5）。
 */
@Component
public class McpHealthTiming {

    /** 禁用语义下的兜底周期（1h；配合 probeAll 入口的显式短路，避免 0/negative 触发调度异常） */
    private static final long DISABLED_FIXED_DELAY_MS = 3_600_000L;

    private final boolean enabled;
    private final long fixedDelayMs;
    private final long probeTimeoutSeconds;

    public McpHealthTiming(
            @Value("${agent.mcp.health-interval-seconds:${AGENT_MCP_HEALTH_INTERVAL_SECONDS:30}}")
            long intervalSeconds,
            @Value("${agent.mcp.health-timeout-seconds:${AGENT_MCP_HEALTH_TIMEOUT_SECONDS:5}}")
            long probeTimeoutSeconds) {
        this.enabled = intervalSeconds > 0;
        this.fixedDelayMs = enabled ? intervalSeconds * 1000L : DISABLED_FIXED_DELAY_MS;
        this.probeTimeoutSeconds = probeTimeoutSeconds;
    }

    /** 供 @Scheduled SpEL 引用的周期（ms） */
    public long probeFixedDelayMs() {
        return fixedDelayMs;
    }

    public boolean enabled() {
        return enabled;
    }

    public long probeTimeoutSeconds() {
        return probeTimeoutSeconds;
    }
}
