package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Redis 配置（session_event 事件流存储，见 docs/api-frontend-sse.md §12）。
 * 环境变量前缀：AGENT_REDIS_*（如 AGENT_REDIS_URL）。
 *
 * <p><b>为什么是独立类而不是 {@link AgentManagerProperties} 的嵌套 record：</b>{@code AgentManagerProperties}
 * 有 16 处测试按位置传参构造，往里加组件会波及全部 16 处；仓库已有独立配置类的先例
 * （{@link SandboxConfig}）。两者都在 {@code AgentFrameworkApplication} 的
 * {@code @EnableConfigurationProperties} 里注册。
 *
 * <p><b>每个叶子都必须有 {@code @DefaultValue}：</b>{@code application.yml} 不在版本控制内
 * （用户本地有未提交改动），代码默认值是唯一保证「配置节缺失也能起来」的东西。
 *
 * <p><b>为什么用单个 url 而不是 host/port/password/database 四个字段：</b>与
 * {@link AgentManagerProperties.CheckpointConfig#jdbcUrl()} 保持同一种形状——一个 URL 承载
 * 地址、库号与凭据（{@code redis://:password@host:6379/0}），少四个字段、少四处拼接。
 */
@ConfigurationProperties(prefix = "agent.redis")
public record AgentRedisProperties(
    /**
     * 连接 URL。默认值与 CHECKPOINT_JDBC_URL 同取向：**本地优先**，平台部署由
     * AGENT_REDIS_URL 环境变量覆盖。K8s 下该变量来自 release env ConfigMap，
     * 不在 manifests/platform.yaml 里（见实施计划的运维步骤）。
     */
    @DefaultValue("redis://127.0.0.1:6379") String url,

    /**
     * 单条命令超时（毫秒）。**必须显式设置**：Lettuce 的
     * {@code TimeoutOptions.DEFAULT_TIMEOUT_COMMANDS} 是 {@code false}——命令超时默认**关闭**，
     * 不设就是「一条命令可以无限期阻塞」。而 {@code RedisURI.DEFAULT_TIMEOUT} 是 60 秒
     * （已用 javap 核对 lettuce-core 6.3.2），所以即使只依赖 URI 默认值，也等于允许
     * 一条命令占住一个 Tomcat 线程 60 秒。本应用是 servlet/Tomcat，线程池有限，必须钳住。
     */
    @DefaultValue("2000") int commandTimeoutMs,

    /** 建连超时（毫秒）。Redis 故障时启动自检与首次写入的等待上限。 */
    @DefaultValue("2000") int connectTimeoutMs,

    /**
     * 单 session 事件流的条数上限（{@code XADD ... MAXLEN ~}），默认 25 万 ≈ 实测最大
     * session（99,816 行）的 2.5 倍。
     *
     * <p><b>这不是可选优化，是内存兜底。</b>只靠 TTL 的话，一个持续活跃的 session
     * 在 7 天窗口内不设上限——按实测最重一小时 223,885 条外推，单个 session 可达 ~14 GB，
     * 足以打爆整个实例。加上这个上限后留存语义是明确的
     * 「min(7 天不活跃, 25 万条/session)」。
     */
    @DefaultValue("250000") int maxLenPerStream
) {}
