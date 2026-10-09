package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * A2A 幂等 Job 配置（Issue #69 路线 A，member 侧 `/a2a/jobs`；env 前缀 `AGENT_A2A_JOB_*`）。
 *
 * <p>独立配置类（沿 {@link AgentRedisProperties} 先例，不波及 {@link AgentManagerProperties}
 * 按位构造测试）。默认全关——存量服务零影响（同 agent-protocol 先例）。
 *
 * @param enabled            总开关（AGENT_A2A_JOB_ENABLED）；true 时 authToken 必填（fail-fast）
 * @param authToken          入口认证 token（AGENT_A2A_JOB_TOKEN；与 /tasks 协议 token 分属两个信任域）
 * @param sendTimeoutSeconds message/send blocking 发送超时秒（AGENT_A2A_JOB_SEND_TIMEOUT_SECONDS）
 * @param retentionHours     完成态映射保留小时（AGENT_A2A_JOB_RETENTION_HOURS，幂等窗口）
 * @param maxConcurrent      并发准入上限（AGENT_A2A_JOB_MAX_CONCURRENT；loopback 自环每 Job 占
 *                           2 个 Tomcat 线程——同步 POST 等 self-call + self-call 跑会话，
 *                           默认 200 线程池下 32 并发余量充足，防自环死锁，Issue §2.5）
 */
@ConfigurationProperties(prefix = "agent.a2a-job")
public record AgentA2aJobProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("") String authToken,
    @DefaultValue("300") int sendTimeoutSeconds,
    @DefaultValue("24") int retentionHours,
    @DefaultValue("32") int maxConcurrent
) {
}
