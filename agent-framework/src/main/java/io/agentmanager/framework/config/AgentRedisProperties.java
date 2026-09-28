package io.agentmanager.framework.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 *
 * <p><b>mode / clusterNodes / prefix（2026-09-28）：</b>支持 Redis Cluster 与 key 前缀隔离，
 * 设计见 docs/redis-cluster-prefix-design.md。
 */
@ConfigurationProperties(prefix = "agent.redis")
public record AgentRedisProperties(
    /**
     * 连接 URL。默认值与 CHECKPOINT_JDBC_URL 同取向：**本地优先**，平台部署由
     * AGENT_REDIS_URL 环境变量覆盖。K8s 下该变量来自 release env ConfigMap，
     * 不在 manifests/platform.yaml 里（见实施计划的运维步骤）。
     *
     * <p>standalone 模式的唯一连接来源；cluster 模式下 {@link #clusterNodes()} 为空时
     * 充当**单种子节点**（拓扑由 CLUSTER SLOTS 自动发现，无需枚举全部节点）。
     * {@code rediss://} TLS 由 Lettuce URI 原生支持。
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
    @DefaultValue("250000") int maxLenPerStream,

    /**
     * 连接模式：{@code standalone} | {@code cluster}。**不含 sentinel**（枚举留扩展位；
     * 真要用时再加分支与配置叶子，先不为用不上的形态付复杂度）。
     *
     * <p>两种模式共用同一套 ClientOptions 语义（命令超时 / 断线 REJECT_COMMANDS）；
     * 差异只在客户端类型（{@code RedisClient} vs {@code RedisClusterClient}）与连接对象。
     */
    @DefaultValue("standalone") Mode mode,

    /**
     * cluster 模式的种子节点，逗号分隔（{@code redis://n1:6379,redis://n2:6379,...}）。
     *
     * <p>**空 = 回落 {@link #url()} 作单种子**——集群拓扑自动发现，通常不需要枚举全部节点；
     * 多种子只是给「种子节点恰好全下线」的场景兜底。standalone 模式下忽略此字段。
     * 元素非法（RedisURI 解析失败）在装配期抛出 → 启动失败早暴露（与「配置错 vs 不可达」
     * 的既有区分一致）。
     */
    @DefaultValue("") String clusterNodes,

    /**
     * key 统一前缀（key 隔离切分，非迁移工具）：非空时所有 Redis key 变为
     * {@code <prefix><原始key>}。**默认空 = 行为与既有部署逐字节一致**。
     *
     * <p>动机：多个 OAF 服务各自起 agent-framework 实例共用一个 oaf-redis 时，
     * {@code sbx:guard:user:{uid}} 会跨服务互锁、sess:* 事件流也可能串扰——各服务配不同
     * prefix 即可隔离。改前缀后旧 key 不可见（靠 TTL 自然消亡），**须在首次部署时定好**。
     *
     * <p>约束：不以 {@code :} 结尾时自动补一个（调用方写 {@code ag1:} 或 {@code ag1} 等价）；
     * 拒绝空白与 {@code {}}（hash tag 语法，混进前缀会与
     * {@link io.agentmanager.framework.service.RedisEventLog} 的 sid hash tag 语义冲突）。
     * 切分实例请用无 {@code {}} 的前缀（如 {@code ag1:}、{@code fw-a:}）。
     */
    @DefaultValue("") String prefix
) {
    /** 连接模式枚举。非法取值由 Spring 枚举绑定在启动期报错。 */
    public enum Mode { standalone, cluster }

    /**
     * 紧凑构造器校验：bind 路径与直接构造路径（测试）都拦得住。
     * {@code mode} 的非法取值不用在这里管——Spring 枚举绑定失败即启动失败，语义相同。
     *
     * <p><b>{@code @ConstructorBinding} 不能省</b>：record 一旦出现第二个构造器（下面的
     * 4 参兼容构造器），Spring 绑定器就无法再自动选定 canonical 构造器，缺了它启动报
     * 「No default constructor found」（AgentRedisPropertiesTest 的 ApplicationContextRunner
     * 用例守着这一条）。
     */
    @ConstructorBinding
    public AgentRedisProperties {
        // 注意 isBlank() 对空串也返回 true，所以空白判断必须限定在非空串上——
        // 空串是合法默认值（不加前缀），只有「非空但全空白」才算配错
        if (prefix != null && !prefix.isEmpty()
                && (prefix.isBlank() || prefix.contains("{") || prefix.contains("}"))) {
            throw new IllegalArgumentException(
                "agent.redis.prefix 非法（\"" + prefix + "\"）：不能为空白、不能含 { 或 }"
                    + "（hash tag 语法会与 sess:{sid} 的 slot 归并语义冲突）");
        }
    }

    /**
     * 兼容既有测试的 4 参构造器（mode=standalone / 无种子 / 无前缀）。
     * {@code RedisEventLogIT} 等 3 处按位置构造的调用点零改动。
     */
    public AgentRedisProperties(String url, int commandTimeoutMs, int connectTimeoutMs, int maxLenPerStream) {
        this(url, commandTimeoutMs, connectTimeoutMs, maxLenPerStream, Mode.standalone, "", "");
    }

    /** 解析 clusterNodes 逗号分隔串为节点串列表（去空白、滤空元素）。 */
    public List<String> clusterNodeList() {
        return Arrays.stream(clusterNodes.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    /**
     * 规范化 prefix：非空且不以 {@code :} 结尾时补 {@code :}（{@code ag1} ≡ {@code ag1:}）。
     * 只在门面取值时调用，record 组件保持原始值（日志可读、bind 语义不被二次加工）。
     */
    public String normalizedPrefix() {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }

    /** 是否 cluster 模式（日志与门面分流用）。 */
    public boolean isCluster() {
        return mode == Mode.cluster;
    }
}
