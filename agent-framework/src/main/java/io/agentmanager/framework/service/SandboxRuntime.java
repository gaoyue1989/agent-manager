package io.agentmanager.framework.service;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.model.OafConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 沙箱运行时生效决策（单一代码路径，所有消费方统一从这里取值）。
 *
 * <p><b>enabled 的三层优先级</b>（解决 OAF 包级开关与部署级 env 的裁决，issue #27）：
 * <ol>
 *   <li>部署级显式意愿：环境变量 {@code SANDBOX_ENABLED} 实际存在（OS env / system property）
 *       → 直接生效。注意 {@code Environment.containsProperty} 只查系统属性源，不会把
 *       application.yml 的 {@code ${SANDBOX_ENABLED:false}} 占位符误判为"已显式设置"</li>
 *   <li>OAF 包级声明：frontmatter {@code config.sandbox.enabled}（{@code OafConfig#packageSandboxEnabled()}），
 *       声明了才参与裁决——包作者对"本包是否需要沙箱"最有发言权</li>
 *   <li>yml 默认：{@code SandboxConfig.enabled()}（绑定 {@code ${SANDBOX_ENABLED:false}}，即未显式设置时为 false）</li>
 * </ol>
 *
 * <p>既有部署（env 显式设置 true/false）行为完全不变；只有删除 env 依赖包声明时新路径才生效。
 */
@Component
public class SandboxRuntime {

    private static final Logger log = LoggerFactory.getLogger(SandboxRuntime.class);

    private final SandboxConfig config;
    private final boolean effectiveEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public SandboxRuntime(SandboxConfig config, OafConfig oafConfig, Environment env) {
        this.config = config;
        this.effectiveEnabled = resolve(config, oafConfig, env);
    }

    /** 测试友好构造：直接指定生效值，绕过三层裁决 */
    public SandboxRuntime(SandboxConfig config, boolean forcedEnabled) {
        this.config = config;
        this.effectiveEnabled = forcedEnabled;
    }

    private static boolean resolve(SandboxConfig config, OafConfig oafConfig, Environment env) {
        if (env.containsProperty("SANDBOX_ENABLED")) {
            boolean v = Boolean.parseBoolean(env.getProperty("SANDBOX_ENABLED"));
            log.info("Sandbox enabled={} (source=SANDBOX_ENABLED env explicit)", v);
            return v;
        }
        Boolean pkg = oafConfig != null ? oafConfig.packageSandboxEnabled() : null;
        if (pkg != null) {
            log.info("Sandbox enabled={} (source=OAF package config.sandbox.enabled)", pkg);
            return pkg;
        }
        log.info("Sandbox enabled={} (source=yml default)", config.enabled());
        return config.enabled();
    }

    /** 沙箱是否生效（AgentScopeConfig 装配 / FileTools / FileController / Debug 等统一消费） */
    public boolean enabled() {
        return effectiveEnabled;
    }

    /**
     * 工作区投影开关（{@code SANDBOX_PROJECTION_ENABLED}，默认 true）。
     * 关闭后 harness 每次 sandbox start 不再把宿主投影目录（AGENTS.md/skills/ 等）
     * hydrate 进容器（harness {@code SandboxFilesystemSpec#workspaceProjectionEnabled}），
     * 显著降低每轮对话的沙箱同步开销（issue #27 缓解项）。skills 依赖强的包不要关。
     */
    public boolean projectionEnabled() {
        return config.projectionEnabled();
    }

    /** 并发执行守卫开关（{@code SANDBOX_GUARD_ENABLED}，默认 true） */
    public boolean guardEnabled() {
        return config.guardEnabled();
    }

    /** 守卫租约 TTL 秒数（{@code SANDBOX_GUARD_LEASE_SECONDS}，默认 900；崩溃后锁到期自愈） */
    public int guardLeaseSeconds() {
        return config.guardLeaseSeconds();
    }

    /**
     * 投影新鲜度检查 stat 指纹缓存（{@code SANDBOX_PROJECTION_FINGERPRINT_CACHE}，默认 true）。
     * 关闭后每次 call 首次 exec 都全量 {@code WorkspaceProjectionApplier.build()}（实测 4.5MB 工作区
     * 17.9ms/18.5MB 47.9ms，含堆内 tar 分配）；开启时指纹命中仅 ~3-5ms（见
     * docs/design/sandbox-skill-sync-optimization-design.md §2.2）。
     */
    public boolean projectionFingerprintCacheEnabled() {
        return config.projectionFingerprintCacheEnabled();
    }

    /** L4 技能 revision 门控（{@code SANDBOX_SKILL_REVISION_GATE}，默认 true；关闭=每次 acquire 全量物化） */
    public boolean skillRevisionGateEnabled() {
        return config.skillRevisionGateEnabled();
    }

    /** 沙箱回写快照差分（{@code SANDBOX_SYNC_SNAPSHOT_DIFF}，默认 true；关闭=每次 call 全量读容器 skills/） */
    public boolean syncSnapshotDiffEnabled() {
        return config.syncSnapshotDiffEnabled();
    }

    /** per-session agent 隔离（{@code SANDBOX_SESSION_AGENT_ISOLATION}，默认 true；#2849 workaround，兼定守卫锁粒度） */
    public boolean sessionAgentIsolationEnabled() {
        return config.sessionAgentIsolation();
    }

    /** 会话级 agent 缓存上限（{@code SANDBOX_SESSION_AGENT_CACHE_MAX}，默认 500） */
    public int sessionAgentCacheMax() {
        return config.sessionAgentCacheMax();
    }

    /** 会话级 agent 空闲过期分钟（{@code SANDBOX_SESSION_AGENT_CACHE_TTL_MINUTES}，默认 30） */
    public int sessionAgentCacheTtlMinutes() {
        return config.sessionAgentCacheTtlMinutes();
    }
}
