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
 * skillRevisionGateEnabled: L4 技能 revision 门控（默认 true；关闭后每次 acquire 全量物化 KV 技能进容器）。
 * syncSnapshotDiffEnabled: 沙箱回写快照差分（默认 true；关闭后每次 call 结束全量读容器 skills/）。
 * enabled 的运行时生效值另经 {@link io.agentmanager.framework.service.SandboxRuntime}
 * 叠加 OAF 包级声明裁决——本字段的绑定值仅是第三优先级（yml 默认）。
 */
@ConfigurationProperties(prefix = "agent.sandbox")
public record SandboxConfig(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("opensandbox/code-interpreter:v1.1.0") String image,
    // 语义升级（sandbox-pool-opensandbox-design §4.7）：生命周期 TTL，默认 60→30
    // （application.yml 占位符为权威默认；本 @DefaultValue 供 yml 段缺失时兜底）
    @DefaultValue("30") int timeoutMinutes,
    /**
     * 建箱/管理面 HTTP 请求超时（秒，{@code SANDBOX_REQUEST_TIMEOUT_SECONDS}，默认 30 =
     * SDK 默认）：并发建箱高峰上游排队放大（oa-1110 复现实测 1.5s→13.2s）——单请求超时
     * 与 create 重试（SANDBOX_CREATE_MAX_ATTEMPTS）叠加消化高峰；语义 ≠
     * {@link #timeoutMinutes}（生命周期 TTL）。0/负值 = 不设置走 SDK 默认
     */
    @DefaultValue("30") int requestTimeoutSeconds,
    @DefaultValue("1024") int memoryMb,
    @DefaultValue("1") int cpuCount,
    @DefaultValue("/opt/code-interpreter/code-interpreter.sh") List<String> entrypoint,
    @DefaultValue("100ms") Duration execdGraceShutdown,
    @DefaultValue("true") boolean projectionEnabled,
    @DefaultValue("true") boolean guardEnabled,
    @DefaultValue("900") int guardLeaseSeconds,
    @DefaultValue("true") boolean skillRevisionGateEnabled,
    @DefaultValue("true") boolean syncSnapshotDiffEnabled,
    /**
     * 包内基线跳过（{@code SANDBOX_SYNC_SKIP_PACKAGE_BASELINE}，默认 true，P1-5）：回写侧
     * 对与包内基线（L2）清单+大小完全一致的容器技能跳过 L4 回写——包内技能经投影进容器后
     * 若照旧回写，会整份复制进每个用户的 KV（66MB/用户），撑爆 agent_fs 并让新沙箱代首次
     * 物化付出全量成本。关闭=回到「容器 skills/ 全量回写」现行为。
     */
    @DefaultValue("true") boolean syncSkipPackageBaselineEnabled,
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
    /**
     * skills-syncd 内部端点鉴权 token（{@code SKILLSYNC_TOKEN}，空 = 端点关闭 404）；
     * bundle 磁盘缓存目录（{@code SKILLSYNC_BUNDLE_CACHE_DIR}，默认 /tmp/skills-bundle-cache）；
     * 沙箱内共享技能库路径（{@code SANDBOX_SKILLS_SHARED_STORE}，空 = 软链农场整体关闭走现行为）
     */
    @DefaultValue("") String skillsSyncToken,
    @DefaultValue("/tmp/skills-bundle-cache") String skillsBundleCacheDir,
    @DefaultValue("") String skillsSharedStore,
    OpenSandboxConfig opensandbox
) {
    /** 绑定入口消歧：record 出现双构造器时 Binder 要求显式指定（与 AgentManagerProperties 同款） */
    @ConstructorBinding
    public SandboxConfig {
    }

    /**
     * 兼容旧按位构造（存量测试传参形态）：包内基线跳过取默认 true
     * （skill-sync-turn-perf P1-5 新增组件排在 syncSnapshotDiffEnabled 之后）；
     * requestTimeoutSeconds 排在 timeoutMinutes 之后（oa-1110-repro-fixes-design §2.3b）。
     */
    public SandboxConfig(boolean enabled, String image, int timeoutMinutes, int requestTimeoutSeconds,
                         int memoryMb, int cpuCount,
                         List<String> entrypoint, Duration execdGraceShutdown,
                         boolean projectionEnabled, boolean guardEnabled, int guardLeaseSeconds,
                         boolean skillRevisionGateEnabled,
                         boolean syncSnapshotDiffEnabled, boolean sessionAgentIsolation,
                         int sessionAgentCacheMax, int sessionAgentCacheTtlMinutes,
                         OpenSandboxConfig opensandbox) {
        this(enabled, image, timeoutMinutes, requestTimeoutSeconds, memoryMb, cpuCount, entrypoint,
            execdGraceShutdown,
            projectionEnabled, guardEnabled, guardLeaseSeconds,
            skillRevisionGateEnabled, syncSnapshotDiffEnabled,
            true, sessionAgentIsolation, sessionAgentCacheMax, sessionAgentCacheTtlMinutes,
            "", "/tmp/skills-bundle-cache", "", opensandbox);
    }

    /**
     * 兼容旧 18 参按位构造（skills-syncd 端点加组件前曾是绑定主构造器，存量测试按此形态传参，
     * 与 AgentManagerProperties 兼容构造器同源问题）：skills-sync token/缓存目录取默认值
     * （token 空 = 端点关闭）。
     */
    public SandboxConfig(boolean enabled, String image, int timeoutMinutes, int requestTimeoutSeconds,
                         int memoryMb, int cpuCount,
                         List<String> entrypoint, Duration execdGraceShutdown,
                         boolean projectionEnabled, boolean guardEnabled, int guardLeaseSeconds,
                         boolean skillRevisionGateEnabled,
                         boolean syncSnapshotDiffEnabled, boolean syncSkipPackageBaselineEnabled,
                         boolean sessionAgentIsolation,
                         int sessionAgentCacheMax, int sessionAgentCacheTtlMinutes,
                         OpenSandboxConfig opensandbox) {
        this(enabled, image, timeoutMinutes, requestTimeoutSeconds, memoryMb, cpuCount, entrypoint,
            execdGraceShutdown,
            projectionEnabled, guardEnabled, guardLeaseSeconds,
            skillRevisionGateEnabled, syncSnapshotDiffEnabled,
            syncSkipPackageBaselineEnabled, sessionAgentIsolation, sessionAgentCacheMax,
            sessionAgentCacheTtlMinutes,
            "", "/tmp/skills-bundle-cache", "", opensandbox);
    }

    public record OpenSandboxConfig(
        String serverUrl,
        String apiKey
    ) {}
}
