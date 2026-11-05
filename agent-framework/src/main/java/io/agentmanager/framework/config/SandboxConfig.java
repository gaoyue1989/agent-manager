package io.agentmanager.framework.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 沙箱配置：SANDBOX_* / OPENSANDBOX_* 环境变量绑定。
 * 未启用（enabled=false）时走默认 RemoteFilesystemSpec 模式。
 * entrypoint: SANDBOX_ENTRYPOINT 逗号分隔（如 "python,main.py"），
 * 默认 /opt/code-interpreter/code-interpreter.sh（镜像默认启动脚本）。
 * execdGraceShutdown: 注入沙箱容器 EXECD_API_GRACE_SHUTDOWN 环境变量，
 * 控制 execd 每条命令 SSE 结束后的尾窗保持时间（默认 1s 过慢，配 100ms 显著提速）。
 * projectionEnabled: 工作区投影开关（issue #27 缓解项，默认 true；关闭后 harness
 * 每次 sandbox start 不再 hydrate 投影目录，skills 依赖强的包不要关）。
 * guardEnabled/guardLeaseSeconds: 并发执行守卫（官方 §9 对 USER 范围的建议，
 * Redis SET NX 串行化同 userId 的沙箱获取；lease 为崩溃自愈 TTL）。
 * projectionFingerprintCacheEnabled: 投影新鲜度检查 stat 指纹缓存（默认 true；关闭后每次 call
 * 全量 WorkspaceProjectionApplier.build()——读投影根全部字节 + sha256 + 堆内建 tar）。
 * skillRevisionGateEnabled: L4 技能 revision 门控（默认 true；关闭后每次 acquire 全量物化 KV 技能进容器）。
 * syncSnapshotDiffEnabled: 沙箱回写快照差分（默认 true；关闭后每次 call 结束全量读容器 skills/）。
 * cpuRequest/memoryRequest: K8s quantity 字符串（D19 资源双档，sandbox-pool-design）——透传 SDK
 * {@code resourceRequests} 作为 Pod requests（缺省时上游 server 会把 resourceLimits 同时当
 * limits/requests，即"每容器全额保障"的根因）；<b>独立于池开关生效</b>，limit 沿用
 * cpuCount/memoryMb。启用后 idle 容器只占 request 水位、借出工作时 burst 到 limit。
 * enabled 的运行时生效值另经 {@link io.agentmanager.framework.service.SandboxRuntime}
 * 叠加 OAF 包级声明裁决——本字段的绑定值仅是第三优先级（yml 默认）。
 */
@ConfigurationProperties(prefix = "agent.sandbox")
public record SandboxConfig(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("opensandbox/code-interpreter:v1.1.0") String image,
    @DefaultValue("60") int timeoutMinutes,
    @DefaultValue("1024") int memoryMb,
    @DefaultValue("1") int cpuCount,
    @DefaultValue("/opt/code-interpreter/code-interpreter.sh") List<String> entrypoint,
    @DefaultValue("100ms") Duration execdGraceShutdown,
    @DefaultValue("true") boolean projectionEnabled,
    @DefaultValue("true") boolean guardEnabled,
    @DefaultValue("900") int guardLeaseSeconds,
    @DefaultValue("true") boolean projectionFingerprintCacheEnabled,
    @DefaultValue("true") boolean skillRevisionGateEnabled,
    @DefaultValue("true") boolean syncSnapshotDiffEnabled,
    /**
     * per-session agent 隔离（{@code SANDBOX_SESSION_AGENT_ISOLATION}，默认 true，2026-10-21 起）：
     * 每会话独立 HarnessAgent 实例（含独立 SandboxBackedFilesystem），消解并行 turn 互踩
     * harness 单槽沙箱指针的跨用户竞态（上游 agentscope-java #2849 的 app 级 workaround）。
     * 同时决定沙箱守卫锁粒度（sessionGranularLocks 同源联动）：开启=锁按会话/隔离键原值
     * （同用户两会话可并发），关闭=锁反查真实用户（共享单例时代的串行保护）。
     * 代价：会话数 × agent 实例内存（MCP 连接经 McpToolRegistrar.sharedWrappers 复用，
     * PR #134 落地，不随会话数放大）。
     */
    @DefaultValue("true") boolean sessionAgentIsolation,
    /** 会话级 agent 缓存上限（{@code SANDBOX_SESSION_AGENT_CACHE_MAX}，默认 500） */
    @DefaultValue("500") int sessionAgentCacheMax,
    /** 会话级 agent 空闲过期分钟（{@code SANDBOX_SESSION_AGENT_CACHE_TTL_MINUTES}，默认 30） */
    @DefaultValue("30") int sessionAgentCacheTtlMinutes,
    /** Pod CPU request（{@code SANDBOX_CPU_REQUEST}，默认 100m；D19 资源双档，K8s quantity 字符串） */
    @DefaultValue("100m") String cpuRequest,
    /** Pod 内存 request（{@code SANDBOX_MEMORY_REQUEST}，默认 128Mi；D19 资源双档，K8s quantity 字符串） */
    @DefaultValue("128Mi") String memoryRequest,
    /**
     * 沙箱池化配置（sandbox-pool-design §5.6，D1 池做在 OpenSandboxClient 契约层）。
     * 默认关闭（enabled=false = 容器按会话建、60min 被 Server GC 的现状行为，逐字节一致）。
     */
    PoolConfig pool,
    OpenSandboxConfig opensandbox
) {
    /**
     * 显式绑定构造器：新增 pool/双档 request 组件后出现多个构造器（下方保留 17 参兼容构造），
     * Binder 在多构造器时要求以 {@code @ConstructorBinding} 指定绑定入口（与
     * AgentManagerProperties 同款处理）。
     */
    @ConstructorBinding
    public SandboxConfig {
        // 配置节缺失（未设任何 SANDBOX_POOL_*）时 Binder 传入 null：归一为全默认（enabled=false），
        // 消费方无需判空
        if (pool == null) {
            pool = PoolConfig.defaults();
        }
    }

    /** 兼容旧 17 参构造（存量测试按位传参）：双档 request 取默认、池配置取默认（关闭 = 现状行为） */
    public SandboxConfig(boolean enabled, String image, int timeoutMinutes, int memoryMb, int cpuCount,
                         List<String> entrypoint, Duration execdGraceShutdown,
                         boolean projectionEnabled, boolean guardEnabled, int guardLeaseSeconds,
                         boolean projectionFingerprintCacheEnabled, boolean skillRevisionGateEnabled,
                         boolean syncSnapshotDiffEnabled, boolean sessionAgentIsolation,
                         int sessionAgentCacheMax, int sessionAgentCacheTtlMinutes,
                         OpenSandboxConfig opensandbox) {
        this(enabled, image, timeoutMinutes, memoryMb, cpuCount, entrypoint, execdGraceShutdown,
            projectionEnabled, guardEnabled, guardLeaseSeconds,
            projectionFingerprintCacheEnabled, skillRevisionGateEnabled, syncSnapshotDiffEnabled,
            sessionAgentIsolation, sessionAgentCacheMax, sessionAgentCacheTtlMinutes,
            "100m", "128Mi", PoolConfig.defaults(), opensandbox);
    }

    public record OpenSandboxConfig(
        String serverUrl,
        String apiKey
    ) {}

    /**
     * 沙箱池参数（env 前缀 {@code SANDBOX_POOL_*}，绑定 agent.sandbox.pool.*）。
     * 设计决议见 agent-framework/docs/sandbox-pool-design.md §2/§5.6。
     */
    public record PoolConfig(
        /** 总开关（{@code SANDBOX_POOL_ENABLED}，默认 false）：关闭走现状，开启走池借出/归还 */
        @DefaultValue("false") boolean enabled,
        /**
         * 初始/保底水位（{@code SANDBOX_POOL_MIN_IDLE}，D18 调整 2→5）：多副本启动经 Lua cap
         * 合力补齐不超额；D18 回缩下限；D19 调度保障底线 = minIdle × request。
         */
        @DefaultValue("5") int minIdle,
        /**
         * 集群级总上限（{@code SANDBOX_POOL_MAX_SIZE}，D18 调整 8→20）：{@code |idle|+|busy|+|creating| ≤ maxSize}。
         * 账本在 Redis（单 ledger HASH），跨副本共享——容量语义对齐 OpenSandbox Server 的共享容器资源，
         * 与副本数解耦（扩副本不动池容量）；D19 峰值竞争 = maxSize × limit（超卖治理旋钮）。
         */
        @DefaultValue("20") int maxSize,
        /** 借出等待上限秒（{@code SANDBOX_POOL_MAX_WAIT_SECONDS}，默认 120；0=无限等不推荐；须 < 守卫租约 900s 与 a2a-job send-timeout 300s） */
        @DefaultValue("120") int maxWaitSeconds,
        /**
         * busy 容器空闲租约 TTL 秒（{@code SANDBOX_POOL_IDLE_RETURN_SECONDS}，默认 600）= session 失活判定阈值。
         * <b>D15 硬不变量：≥ 确认截止（{@code AGENT_CONFIRM_DEADLINE_MINUTES}，默认 10min）</b>，默认等值；
         * 等值安全性由确认入口预检续约（D16-④ touch）保证。违反该不变量拒绝启动（fail-fast）。
         */
        @DefaultValue("600") int idleReturnSeconds,
        /** 回收/补位/保活/水位扫描周期秒（{@code SANDBOX_POOL_SCAN_INTERVAL_SECONDS}，默认 60） */
        @DefaultValue("60") int scanIntervalSeconds,
        /** 补位创建并发上限（{@code SANDBOX_POOL_REBUILD_CONCURRENCY}，默认 2；集群级，经 Lua cap 裁决） */
        @DefaultValue("2") int rebuildConcurrency,
        /**
         * 单用户容器配额（{@code SANDBOX_POOL_USER_MAX_CONTAINERS}，默认 5；D17）：含粘滞空闲与使用中，
         * 集群级 ledger 计数——配额满 LRU 继承该用户粘滞空闲，无粘滞空闲报 {@code user_quota_exhausted}。
         * 建议满足 {@code maxSize ≥ 2×userMaxContainers} 并按并发用户数规划。
         */
        @DefaultValue("5") int userMaxContainers,
        /**
         * 粘滞空闲保留窗口秒（{@code SANDBOX_POOL_STICKY_TTL_SECONDS}，默认 600；D17）：归还容器不 kill，
         * 以 owner=userKey 转粘滞空闲等待同用户继承/原会话复活，到期 kill 释放配额与容量。
         * 与 idleReturnSeconds 同值默认，语义统一"10 分钟无动静 = 会话结束"。
         */
        @DefaultValue("600") int stickyTtlSeconds,
        /**
         * 弹性水位扩/缩步长（{@code SANDBOX_POOL_GROW_STEP}，默认 5；D18）：利用率 ≥80% 抬升、
         * <50%×3 周期+冷却回缩（高低水位/周期/冷却为内置常量，有需要再 env 化）。
         */
        @DefaultValue("5") int growStep
    ) {
        /** 测试/代码缺省（与 @DefaultValue 一致） */
        public static PoolConfig defaults() {
            return new PoolConfig(false, 5, 20, 120, 600, 60, 2, 5, 600, 5);
        }
    }
}
