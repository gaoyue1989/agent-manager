package io.agentmanager.framework.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

import io.agentmanager.framework.config.AgentRedisProperties;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec;
import io.agentmanager.framework.sandbox.opensandbox.WorkspaceSyncService;
import io.agentmanager.framework.service.*;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.mysql.store.JdbcStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;

@Configuration
public class AgentScopeConfig {
    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

    /**
     * 沙箱文件系统（SandboxRuntime.enabled() 时装配，issue #27）。
     * enabled 生效值经 SandboxRuntime 三层裁决（SANDBOX_ENABLED env 显式 > OAF 包声明
     * config.sandbox.enabled > yml 默认）。装配两个 harness 官方能力：
     * - workspaceProjectionEnabled：投影开关（SANDBOX_PROJECTION_ENABLED，默认 true）
     * - executionGuard：Redis 版 SandboxExecutionGuard（SANDBOX_GUARD_ENABLED，默认 true）
     */
    @Bean
    public OpenSandboxFilesystemSpec sandboxFilesystemSpec(
        SandboxConfig config,
        io.agentmanager.framework.service.SandboxRuntime sandboxRuntime,
        io.agentmanager.framework.service.WorkspaceReader workspaceReader,
        WorkspaceSyncService workspaceSyncService,
        io.agentmanager.framework.service.FileAssetStore fileAssetStore,
        io.agentmanager.framework.service.SkillManageService skillManageService,
        io.agentmanager.framework.service.storage.FileStorage fileStorage,
        SessionUserStore sessionUserStore,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.redis.RedisConnectionFacade> redisFacadeProvider,
        org.springframework.beans.factory.ObjectProvider<SandboxLifecycleConfig> lifecycleConfigProvider,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.sandbox.opensandbox.SandboxLifecycleManager> lifecycleManagerProvider
    ) {
        if (!sandboxRuntime.enabled()) {
            log.info("Sandbox disabled (SandboxRuntime), using RemoteFilesystemSpec mode");
            return null;
        }
        log.info("Sandbox enabled, assembling OpenSandboxFilesystemSpec (server={}, image={})",
            config.opensandbox().serverUrl(), config.image());
        // 业务规范键解析（issue #52）：Channel 链路 ctx.userId 是网关 peer（=前端 sessionId），
        // 沙箱 userKey 须经 session_user 反查真实用户，否则 per-user 技能物化/回写退化为
        // per-session 命名空间。与 HarnessAgentFactory 的 LLM 记录/span 翻译同一实现（memo 共享）。
        var sessionKeyResolver = new SessionKeyResolver(sessionUserStore);
        var spec = new OpenSandboxFilesystemSpec()
            .serverUrl(config.opensandbox().serverUrl())
            .apiKey(config.opensandbox().apiKey())
            .image(config.image())
            .timeout(Duration.ofMinutes(config.timeoutMinutes()))
            .entrypoint(config.entrypoint())
            .resource(Map.of(
                "cpu", String.valueOf(config.cpuCount()),
                "memory", config.memoryMb() + "Mi"
            ))
            .environment(Map.of(
                "EXECD_API_GRACE_SHUTDOWN", config.execdGraceShutdown().toMillis() + "ms"
            ))
            .workspaceReader(workspaceReader)
            .workspaceSyncService(workspaceSyncService)
            .fileAssetStore(fileAssetStore)
            .fileStorage(fileStorage)
            .isolationScope(IsolationScope.USER);
        spec.setSessionKeyResolver(sessionKeyResolver);
        // 工作区投影开关（issue #27 缓解项）：SANDBOX_PROJECTION_ENABLED=false 时 sandbox start
        // 不再 hydrate 投影目录，降低每轮对话的沙箱同步开销（skills 依赖强的包不要关）
        spec.workspaceProjectionEnabled(sandboxRuntime.projectionEnabled());
        // 技能缓存投影收窄（方案 1）：SDK 默认根含整棵 .skills-cache，按 scope 分片且跨会话/
        // 跨用户共享，整棵投影会把所有历史 scope 灌进容器（体积大 + 跨用户泄漏）。此处从 SDK
        // 投影根剔除 .skills-cache，改由 OpenSandbox.hydrateScopedSkillCache 只投放当前会话
        // scope（.skills-cache/<scope>）并清理容器内其它 scope 残留。
        spec.workspaceProjectionRoots(List.of("AGENTS.md", "skills", "subagents", "knowledge"));
        // 并发执行守卫（issue #27c，官方 §9 对 USER 范围的建议）：Redis SET NX 串行化同用户
        // 的沙箱获取，消解并发 hydrate 互踩与端口竞态的触发面；Redis 不可用时守卫内部 fail-open。
        // 锁键经 session_user 反查真实用户（Channel 链路 key.value 是网关 peer=前端 sessionId，
        // 直接组键会让 USER 串行化退化为每会话一把，2026-10-16 实测确认）；无 store 时回退原值
        // 2026-10-21：per-session agent 隔离开启后 slot 已按会话分（Channel=前端 sid），锁粒度
        // 跟随 slot（sessionGranularLocks=true），否则同用户多会话在各自独立 slot 上互相排队
        var guardEnabled = sandboxRuntime.guardEnabled();
        io.agentmanager.framework.sandbox.opensandbox.RedisSandboxExecutionGuard guard = null;
        if (guardEnabled) {
            var redisFacade = redisFacadeProvider.getIfAvailable();
            if (redisFacade != null) {
                // scoped 指纹跨副本共享层（first-llm-latency 缺口④）：sandboxId|scope → 指纹放
                // Redis，同会话轮转到新副本不再重付 50MB 慢路径；fail-open（不可用回落 Pod 级）
                spec.sharedFingerprintStore(new io.agentmanager.framework.sandbox.opensandbox.ScopedFingerprintStore(redisFacade));
                // 指纹源用包内技能目录（PVC 只读同挂载→跨副本一致）：SkillManageService 与
                // WorkspaceSyncService 同源（configDir/skills）
                spec.packageSkillsDir(skillManageService.getSkillsDir());
                var sessionGranular = sandboxRuntime.sessionAgentIsolationEnabled();
                guard = new io.agentmanager.framework.sandbox.opensandbox.RedisSandboxExecutionGuard(
                    redisFacade, sandboxRuntime.guardLeaseSeconds() * 1000L, sessionUserStore, sessionGranular);
                spec.executionGuard(guard);
                log.info("SandboxExecutionGuard enabled (scope=USER, lockGranularity={}, leaseTtl={}s)",
                    sessionGranular ? "per-session(slot-aligned)" : "per-real-user", sandboxRuntime.guardLeaseSeconds());
            } else {
                log.warn("SandboxExecutionGuard requested (SANDBOX_GUARD_ENABLED) but no RedisConnectionFacade bean — running unguarded");
            }
        }
        // 请求级 userId 注入：middleware 与 acquire 同一订阅链，顺序执行
        spec.setUserKeyMiddleware(new io.agentmanager.framework.sandbox.opensandbox.SandboxUserKeyMiddleware(
            spec, sessionKeyResolver));
        // 沙箱生命周期装配（sandbox-pool-opensandbox-design §4.7/§4.9，PR-D）：
        // lifecycle 开启时构建 Store/Registry/Manager 三件套并注入 spec（client/stop()/guard
        // 经 spec 取用）；guard 是生命周期身份捕获的前置条件（评审 d3）：guard 关闭=Noop 守卫
        // → tryEnter 注入点消失 → acquire 时 sid 为空 → ① 恒 miss 每轮新建 → 配额被孤儿
        // 绑定占满出假 409——此处 fail-fast 早失败早发现，不接受静默退化。
        var lifecycle = lifecycleConfig(lifecycleConfigProvider);
        if (lifecycle.enabled()) {
            validateLifecycle(config, lifecycle);
            if (guard == null) {
                throw new IllegalStateException(
                    "SANDBOX_LIFECYCLE_ENABLED=true requires SANDBOX_GUARD_ENABLED=true with a usable RedisConnectionFacade "
                        + "(lifecycle identity capture runs on guard tryEnter thread; guard off = capture point gone = "
                        + "every acquire misses ① and quota fills with orphan bindings). Either enable the guard or set "
                        + "SANDBOX_LIFECYCLE_ENABLED=false.");
            }
            // 三件套为独立 @Bean（控制器/debug 端点/清理任务按类型注入，普通 new 的对象 Spring 不可见）：
            // manager 缺失即装配自洽被破坏，fail-fast 拒绝静默降级
            var manager = lifecycleManagerProvider.getIfAvailable();
            if (manager == null) {
                throw new IllegalStateException(
                    "SandboxLifecycle enabled but SandboxLifecycleManager bean missing (assembly broken; "
                        + "check sandboxBindingStore/sandboxRenewalRegistry bean conditions)");
            }
            spec.setLifecycleManager(manager);
            guard.setLifecycleIdentitySpec(spec);   // 守卫 tryEnter 同线程注入捕获点（§4.2）
            log.info("SandboxLifecycle enabled (quota={}, ttlMinutes={}, renewInterval={}s, holdMinutes={}m, poolRef={})",
                lifecycle.userQuota(), config.timeoutMinutes(), lifecycle.renewIntervalSeconds(),
                lifecycle.confirmHoldMinutes(), lifecycle.poolRef());
        }
        return spec;
    }

    /**
     * 沙箱生命周期三件套 @Bean（须为 Spring bean——DebugApiController/ChatStreamController/
     * ConfirmController/AguiRunController/SessionCleanupService/SandboxLocator 均按类型注入；
     * 普通对象 Spring 不可见，注入点全部静默落空，2026-11-08 本地 lifecycle 组冒烟实锤）。
     * 条件与 sandboxFilesystemSpec 同源：sandboxRuntime.enabled() && lifecycle.enabled()，
     * 不满足返回 null（NullBean——注入点 required=false 落 null，生命周期旁路）。
     */
    @Bean
    public io.agentmanager.framework.sandbox.opensandbox.SandboxBindingStore sandboxBindingStore(
        DataSource dataSource,
        io.agentmanager.framework.service.SandboxRuntime sandboxRuntime,
        org.springframework.beans.factory.ObjectProvider<SandboxLifecycleConfig> lifecycleConfigProvider
    ) {
        var lifecycle = lifecycleConfig(lifecycleConfigProvider);
        if (!sandboxRuntime.enabled() || !lifecycle.enabled()) {
            return null;
        }
        return new io.agentmanager.framework.sandbox.opensandbox.SandboxBindingStore(dataSource);
    }

    @Bean
    public io.agentmanager.framework.sandbox.opensandbox.SandboxRenewalRegistry sandboxRenewalRegistry(
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.sandbox.opensandbox.SandboxBindingStore> storeProvider,
        io.agentmanager.framework.service.SandboxRuntime sandboxRuntime,
        org.springframework.beans.factory.ObjectProvider<SandboxLifecycleConfig> lifecycleConfigProvider
    ) {
        var store = storeProvider.getIfAvailable();
        var lifecycle = lifecycleConfig(lifecycleConfigProvider);
        if (store == null || !sandboxRuntime.enabled() || !lifecycle.enabled()) {
            return null;
        }
        // 事件汇初值 null，Manager bean 建好后桥接（emit→Manager 环形缓冲统一落事件）；
        // daemon 守护线程在此启动（JVM 退出自然终止；sweep 异常自捕获不影响主流程）
        var registry = new io.agentmanager.framework.sandbox.opensandbox.SandboxRenewalRegistry(
            store, lifecycle.renewIntervalSeconds(), lifecycle.confirmHoldMinutes(), null);
        registry.start();
        return registry;
    }

    @Bean
    public io.agentmanager.framework.sandbox.opensandbox.SandboxLifecycleManager sandboxLifecycleManager(
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.sandbox.opensandbox.SandboxBindingStore> storeProvider,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.sandbox.opensandbox.SandboxRenewalRegistry> registryProvider,
        SandboxConfig config,
        org.springframework.beans.factory.ObjectProvider<SandboxLifecycleConfig> lifecycleConfigProvider,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.service.TurnLeaseStore> turnLeaseStoreProvider,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.service.ConfirmContextStore> confirmContextStoreProvider,
        io.agentmanager.framework.service.SandboxRuntime sandboxRuntime
    ) {
        var store = storeProvider.getIfAvailable();
        var registry = registryProvider.getIfAvailable();
        var lifecycle = lifecycleConfig(lifecycleConfigProvider);
        if (store == null || registry == null || !sandboxRuntime.enabled() || !lifecycle.enabled()) {
            return null;
        }
        var manager = new io.agentmanager.framework.sandbox.opensandbox.SandboxLifecycleManager(
            store, registry, lifecycle, config.timeoutMinutes(),
            turnLeaseStoreProvider.getIfAvailable(), confirmContextStoreProvider.getIfAvailable());
        registry.setEventSink(manager::recordLifecycleEvent);
        return manager;
    }

    /** 生命周期配置取值（独立 properties bean；无 provider/bean 时回落默认构造，与 §4.7 默认一致） */
    private static io.agentmanager.framework.config.SandboxLifecycleConfig lifecycleConfig(
        org.springframework.beans.factory.ObjectProvider<SandboxLifecycleConfig> provider) {
        var cfg = provider != null ? provider.getIfAvailable() : null;
        return cfg != null ? cfg : new io.agentmanager.framework.config.SandboxLifecycleConfig(true, "", 5, 60, 20);
    }

    /**
     * §4.7 启动校验（U3）：{@code TTL_MINUTES ≥ 1}（上游 create timeout 下限 60s）与
     * {@code RENEW_INTERVAL < TTL×60/2}（续期间隔必须显著小于 TTL，一半即失效）——
     * 违规 fail-fast，不接受静默生效。public 供 U3 单测直调。
     */
    public static void validateLifecycle(SandboxConfig config, SandboxLifecycleConfig lifecycle) {
        if (config.timeoutMinutes() < 1) {
            throw new IllegalStateException("SANDBOX_TIMEOUT_MINUTES must be >= 1 (upstream create timeout lower "
                + "bound is 60s), got " + config.timeoutMinutes());
        }
        if (lifecycle.renewIntervalSeconds() * 2 >= config.timeoutMinutes() * 60L) {
            throw new IllegalStateException("SANDBOX_RENEW_INTERVAL_SECONDS must be < SANDBOX_TIMEOUT_MINUTES*60/2 "
                + "(renew interval must be well under TTL), got interval=" + lifecycle.renewIntervalSeconds()
                + "s ttl=" + config.timeoutMinutes() + "min");
        }
    }

    /**
     * 沙箱回写服务：依赖 WorkspaceReader（而非裸 BaseStore），保证回写命名空间与读取侧一致
     * ——{@code agents/{agent}/users/{uid}}（运行时文件）与
     * {@code agents/{agent}/users/{uid}/skills}（用户技能 L4，key={@code /{技能名}/{路径}}）。
     * 快照差分开关（SANDBOX_SYNC_SNAPSHOT_DIFF，默认 true）与包内基线跳过
     * （SANDBOX_SYNC_SKIP_PACKAGE_BASELINE，默认 true，P1-5：L4 只存用户真实覆盖，不复制
     * 包内基线副本）经 SandboxRuntime 传入；基线目录取 SkillManageService（L2，/config/skills）。
     */
    @Bean
    public WorkspaceSyncService workspaceSyncService(
            io.agentmanager.framework.service.WorkspaceReader workspaceReader,
            io.agentmanager.framework.service.SandboxRuntime sandboxRuntime,
            io.agentmanager.framework.service.SkillManageService skillManageService) {
        return new WorkspaceSyncService(workspaceReader, sandboxRuntime.syncSnapshotDiffEnabled(),
            skillManageService.getSkillsDir(), sandboxRuntime.syncSkipPackageBaselineEnabled());
    }

    /**
     * 工作区读写器：注入 agentName 以对齐框架 RemoteFilesystemSpec(USER) 的 KV 命名空间
     * （{@code agents/{agentName}/users/{userId}/...}）——否则本类写入与框架读取落在不同命名空间，
     * 导致 present_file 读不到 write_file 的结果（e2e-ci-plan §11.3 D3 第二处断裂）。
     *
     * <p>显式建 Bean（而非 @Service 组件扫描），以保证 agentName 一定来自 OAF 配置。
     */
    @Bean
    public io.agentmanager.framework.service.WorkspaceReader workspaceReader(
            DistributedStore distributedStore, OafConfig oafConfig, AgentManagerProperties props) {
        var harness = props.harness() != null ? props.harness() : AgentManagerProperties.HarnessConfig.defaults();
        // 记忆总开关传入 WorkspaceReader：false 时运行时文件读取返回空集，
        // 沙箱注入链路（OpenSandbox.injectRuntimeFilesIfNeeded）拿空文件集后天然 no-op
        return new io.agentmanager.framework.service.WorkspaceReader(distributedStore, oafConfig.name(),
            harness.memoryEnabled());
    }

    @Bean
    public OafConfig oafConfig(OafConfigLoader loader) {
        var config = loader.load();
        log.info("Loaded OAF: {} v{}", config.name(), config.version());
        log.info("  Skills: {} - {}", config.skills().size(),
            config.skills().stream().map(OafConfig.SkillConfig::name).toList());
        log.info("  MCP: {} - {}", config.mcpServers().size(),
            config.mcpServers().stream().map(OafConfig.McpServerConfig::server).toList());
        log.info("  Tools: {}", config.tools());
        return config;
    }

    /**
     * OAF 配置动态门面：启动时以初始 {@code OafConfig} 初始化；
     * reload（OafReloadService）成功后原子替换为重新解析的实例。
     * 需要感知配置更新的读取点注入本 holder。
     */
    @Bean
    public OafConfigHolder oafConfigHolder(OafConfig oafConfig) {
        return new OafConfigHolder(oafConfig);
    }

    @Bean
    public McpManager mcpManager(AgentManagerProperties props, McpToolRegistrar mcpToolRegistrar) {
        return new McpManager(Path.of(props.configDir()), mcpToolRegistrar);
    }

    @Bean
    public List<Map<String, Object>> mcpConfigs(McpManager mcpManager, OafConfig oafConfig) {
        return mcpManager.loadConfigs(oafConfig.mcpServers());
    }

    @Bean
    public A2uiService a2uiService(OafConfig oafConfig) {
        return new A2uiService(oafConfig.getCatalogId());
    }

    @Bean
    public LLMLogger llmLogger() {
        return new LLMLogger();
    }

    @Bean
    public DataSource dataSource(AgentManagerProperties props) {
        var cp = props.checkpoint();
        var pool = props.harness() != null ? props.harness() : AgentManagerProperties.HarnessConfig.defaults();
        var ds = new HikariDataSource();
        ds.setJdbcUrl(cp.jdbcUrl());
        ds.setUsername(cp.username());
        ds.setPassword(cp.password());
        ds.setMaximumPoolSize(pool.dbPoolMaxSize());
        ds.setMinimumIdle(pool.dbPoolMinIdle());
        ds.setConnectionTimeout(pool.dbPoolConnectionTimeoutMs());
        ds.setIdleTimeout(pool.dbPoolIdleTimeoutMs());
        ds.setMaxLifetime(pool.dbPoolMaxLifetimeMs());
        return ds;
    }

    /**
     * Redis 连接门面（session_event 事件流存储，见 docs/api-frontend-sse.md §12）。
     * 按 {@code agent.redis.mode} 分流 standalone / cluster 客户端，统一收拢
     * ClientOptions、惰性连接 + 建连退避、key 前缀与首连自检调度（原 redisClient bean
     * 职责的超集，迁移见 docs/redis-cluster-prefix-design.md）。
     *
     * <p><b>这里只建客户端，不建连接。</b>Lettuce 是懒连接的，所以 Redis 不可达**不会**让启动失败；
     * 真正的连接（以及随之而来的启动自检）发生在首次使用时。这是刻意的：
     * Redis 的一次滚动重启不能变成整个 agent 集群的崩溃循环。
     *
     * <p>与「不可达」相对的是「配置错」：URL 非法时 {@code RedisURI.create} 抛
     * {@link IllegalArgumentException}，bean 创建失败 → 启动失败。那属于打包/发布错误，
     * 早失败早发现，两者必须区别对待。
     */
    @Bean(destroyMethod = "close")
    public io.agentmanager.framework.redis.RedisConnectionFacade redisConnectionFacade(
            AgentRedisProperties redis) {
        var facade = io.agentmanager.framework.redis.RedisConnectionFacade.create(redis);
        // 首连预热（first-llm-latency P0）：facade 惰性建连——不预热时首次建连（lettuce 握手 +
        // 首连钩子，实测 ≈0.8s）落在首个会话的 session-agent 创建路径上，计入首推理前延迟。
        // 后台线程提前建连，不阻塞启动；失败静默（惰性重试语义不变）
        Thread.ofPlatform().name("redis-warmup").daemon(true).start(() -> {
            try {
                facade.sync().ping();
            } catch (Exception e) {
                // 预热失败不影响启动：首用时惰性建连（含退避）语义不变
            }
        });
        return facade;
    }

    /**
     * 分布式存储：agent_state 表 (AgentState) + agent_fs 表 (工作区文件)。
     * 使用自定义库名/表名与现有基础设施保持一致。
     * AgentStateStore 使用 SandboxAwareMysqlAgentStateStore：官方 MysqlAgentStateStore
     * 拒绝含 "/" 的 ID，而沙箱 slot ID 形如 sandbox/user/{agentId}/{userId（框架固定格式），
     * 需放宽校验以支持沙箱状态持久化。
     *
     * <p><b>@DependsOn("flywayInitializer") 不可省</b>：SDK store 构造期就会建
     * agent_state/agent_fs（CREATE IF NOT EXISTS），若抢在 Flyway 迁移之前执行，全新库上
     * Flyway 会因 schema 非空走 baseline-on-migrate 路径直接基线收场——V1..V5 全部跳过，
     * session_user/turn_lease 等表缺失，对话链路静默瘫痪（2026-09-28 CI E2E 四 job 全红根因）。
     */
    @Bean
    @org.springframework.context.annotation.DependsOn("flywayInitializer")
    public DistributedStore distributedStore(DataSource dataSource, AgentManagerProperties props,
            io.agentmanager.framework.service.SessionMessageStore sessionMessageStore,
            io.agentmanager.framework.config.HistoryConfig historyConfig) {
        var dbName = props.checkpoint().resolvedDbName();
        // AgentStateStore 装饰链（自内向外，顺序不可换）：
        //   ① SandboxAwareMysqlAgentStateStore —— 真实 MySQL 存储（放宽沙箱 slot ID 校验）
        //   ② SessionMessageArchiveStateStore —— 会话消息轨 write-through 归档（压缩后历史可查，
        //      docs/session-history-archive-design.md）；须在 Backfill 之内才拿得到回填后形态
        //   ③ AskingContentBackfillStateStore —— 持久化前把 ASKING tool_use 块 content 回填为
        //      input 的 JSON 字符串——修复 HITL 恢复时 ToolValidator 抛 'argument "content" is null'
        io.agentscope.core.state.AgentStateStore agentStateStore =
            new io.agentmanager.framework.sandbox.opensandbox.SandboxAwareMysqlAgentStateStore(
                dataSource, dbName, "agent_state", true);
        if (historyConfig == null || historyConfig.archiveEnabled()) {
            agentStateStore = new io.agentmanager.framework.service.SessionMessageArchiveStateStore(
                agentStateStore, sessionMessageStore);
        }
        var store = DistributedStore.builder()
            .agentStateStore(new io.agentmanager.framework.sandbox.opensandbox.AskingContentBackfillStateStore(
                agentStateStore))
            .baseStore(JdbcStore.builder(dataSource)
                .tableName("agent_fs")
                .initializeSchema(true)
                .build())
            .build();
        log.info("DistributedStore initialized ({} . agent_state + agent_fs, historyArchive={})",
            dbName, historyConfig == null || historyConfig.archiveEnabled());
        return store;
    }

    @Bean
    public io.agentmanager.framework.tool.BusinessTools businessTools() {
        return new io.agentmanager.framework.tool.BusinessTools();
    }

    /**
     * 沙箱状态读取（PUT /sandbox/env 定位容器用，docs/sandbox-env-update-design.md §5.1）：
     * 与 HarnessAgent 内部同源——同一 AgentStateStore + 同一 agentId（OafConfig.name()），
     * 保证按同一槽位键读到 harness 持久化的 SandboxState。
     */
    @Bean
    public io.agentscope.harness.agent.sandbox.SessionSandboxStateStore sessionSandboxStateStore(
            DistributedStore distributedStore, OafConfig oafConfig) {
        return new io.agentscope.harness.agent.sandbox.SessionSandboxStateStore(
            distributedStore.agentStateStore(), oafConfig.name());
    }

    @Bean
    public io.agentmanager.framework.tool.FileTools fileTools(
        io.agentmanager.framework.service.FileAssetStore fileAssetStore,
        io.agentmanager.framework.service.storage.FileStorage fileStorage,
        io.agentmanager.framework.config.AgentManagerProperties props,
        io.agentmanager.framework.service.SandboxRuntime sandboxRuntime,
        io.agentmanager.framework.service.WorkspaceReader workspaceReader,
        SessionUserStore sessionUserStore
    ) {
        // 规范键解析（issue #52）：present_file 沙箱归属校验须与 SandboxUserKeyMiddleware
        // 同源反查真实用户，否则 Channel 链路 ctx.userId（网关 peer）与沙箱绑定键不等 → 直读被误拒
        var sessionKeyResolver = new SessionKeyResolver(sessionUserStore);
        // 沙箱部署注册无 file_content_base64 参数的形态：base64 回退要求 LLM 在 tool call
        // 参数里复述文件全文，大文件必超输出上限被截断 → 工具报错 → file_ready 静默丢失
        if (sandboxRuntime.enabled()) {
            return new io.agentmanager.framework.tool.FileTools.SandboxAwareFileTools(
                fileAssetStore, fileStorage, props, sandboxRuntime, workspaceReader, sessionKeyResolver);
        }
        return new io.agentmanager.framework.tool.FileTools(fileAssetStore, fileStorage, props,
            sandboxRuntime, workspaceReader, sessionKeyResolver);
    }

    /**
     * 自定义工具集合：在此注册 @Tool 注解的工具类。
     * HarnessAgent 创建时会注册到 Toolkit。
     * 使用特定 List<BusinessTools> 类型避免收集全部 Bean 造成循环依赖；
     * FileTools 与 BusinessTools 无继承关系，故这里用 Object 泛型显式聚合
     * （Spring 对 List<Object> 参数仍按参数类型注入，不自动收集——只有无参
     * 或按 Object 类型自动装配时才会收集全部 Bean，此处显式声明参数安全）。
     */
    @Bean
    public List<io.agentmanager.framework.tool.CustomTool> customTools(
        io.agentmanager.framework.tool.BusinessTools businessTools,
        io.agentmanager.framework.tool.FileTools fileTools
    ) {
        // 泛型收窄为 CustomTool 标记接口：List<Object> 会把全部 Bean 扫为候选，
        // 与 OafReloadService/HarnessAgentFactory 形成循环依赖（见 CustomTool javadoc）
        return java.util.Arrays.asList(businessTools, fileTools);
    }

    /**
     * 内置工具运行时注册表（issue #28）：/tools includeInternal 中 CustomTool 段的事实源。
     * 与 customTools 同源（同一批 @Tool bean），deniedTools 类粒度剔除语义与 harnessAgent
     * 装配一致；经 OafConfigHolder 每请求取值，OAF reload（deniedTools 变更）即时反映。
     * SDK（Harness 框架）自注册的内置工具不在此列——经
     * {@link io.agentmanager.framework.service.InternalToolRegistry#listSdkInternalTools}
     * 从运行中 agent 的 Toolkit 实际注册集透出（issue #39 拆字段口径）。
     */
    @Bean
    public io.agentmanager.framework.service.InternalToolRegistry internalToolRegistry(
        OafConfigHolder oafConfigHolder,
        List<io.agentmanager.framework.tool.CustomTool> customTools
    ) {
        return new io.agentmanager.framework.service.InternalToolRegistry(oafConfigHolder, customTools);
    }

    /** 按 LLM 配置构建 ChatModel（装配逻辑见 {@link ChatModelFactory}，托管/系统模型共用同一口径） */
    io.agentscope.extensions.model.openai.OpenAIChatModel buildChatModel(
        AgentManagerProperties.LLMConfig llm,
        AgentManagerProperties.HarnessConfig harness) {
        return ChatModelFactory.build(llm, harness);
    }

    /**
     * 会话标题生成专用模型：与主对话模型同配置的独立实例（**系统模型**，LLM_* 环境变量），
     * 供 {@link io.agentmanager.framework.service.SessionTitleService} 在会话首条消息后异步生成标题。
     * 不参与 HarnessAgent 装配（独立实例，避免影响主链路追踪/日志包装）；
     * 叠加 TracingModelWrapper：标题调用绕过 middleware 链，补 OTel span（title）。
     */
    @Bean
    public io.agentscope.core.model.Model titleGenerationModel(AgentManagerProperties props) {
        var llm = props.llm();
        var harness = props.harness() != null ? props.harness() : AgentManagerProperties.HarnessConfig.defaults();
        return new io.agentmanager.framework.service.TracingModelWrapper(
            ChatModelFactory.build(llm, harness), "title");
    }

    /**
     * HarnessAgent 单例：构建逻辑在 {@link HarnessAgentFactory}（启动与 reload 共用同一装配代码）。
     * reload 时 OafReloadService 调 factory.build(...) 重建并经 AgentRuntimeService/A2A
     * 的 holder 原子切换引用，本 Bean 引用保持启动实例不变。
     */
    @Bean
    public HarnessAgent harnessAgent(
        AgentManagerProperties props,
        HarnessAgentFactory harnessAgentFactory,
        DistributedStore distributedStore,
        OafConfig oafConfig,
        LLMLogger llmLogger,
        UiContextStore uiContextStore,
        SessionUserStore sessionUserStore,
        io.agentmanager.framework.service.ModelCatalog modelCatalog,
        @Autowired(required = false) OpenSandboxFilesystemSpec sandboxSpec
    ) {
        return harnessAgentFactory.build(oafConfig, distributedStore, llmLogger,
            uiContextStore, sessionUserStore, modelCatalog, sandboxSpec);
    }

    /**
     * 会话标题生成服务：装配 master 版实现（@Qualifier("titleGenerationModel") 注入 + 并发去重 + 空结果回退）。
     * 标题固定使用系统模型，不受会话级模型切换影响。
     */
    @Bean
    public io.agentmanager.framework.service.SessionTitleService sessionTitleService(
            io.agentscope.core.model.Model titleGenerationModel,
            SessionUserStore sessionUserStore) {
        return new io.agentmanager.framework.service.SessionTitleService(
            titleGenerationModel, sessionUserStore);
    }

    @Bean
    public io.agentmanager.framework.service.ConfirmContextStore confirmContextStore(DataSource dataSource,
            AgentManagerProperties props) {
        var cleanup = props.cleanup();
        var ttl = cleanup != null ? cleanup.confirmTtlMinutes() : 30;
        return new io.agentmanager.framework.service.ConfirmContextStore(dataSource,
            java.time.Duration.ofMinutes(ttl));
    }

    @Bean
    public io.agentmanager.framework.service.TurnLeaseStore turnLeaseStore(DataSource dataSource,
            AgentManagerProperties props) {
        var cleanup = props.cleanup();
        var ttl = cleanup != null ? cleanup.turnLeaseTtlSeconds() : 60;
        var renew = cleanup != null ? cleanup.turnLeaseRenewSeconds() : 20;
        return new io.agentmanager.framework.service.TurnLeaseStore(dataSource,
            java.time.Duration.ofSeconds(ttl),
            java.time.Duration.ofSeconds(renew));
    }

    @Bean
    public io.agentmanager.framework.service.ToolAuditStore toolAuditStore(DataSource dataSource,
            AgentManagerProperties props) {
        var cleanup = props.cleanup();
        var retention = cleanup != null ? cleanup.auditRetentionDays() : 30;
        return new io.agentmanager.framework.service.ToolAuditStore(dataSource, retention);
    }

    /**
     * session_event 的 Redis Streams 存储层（只有它需要真 Redis）。
     * 惰性连接：这里不建连，所以 Redis 不可达不会影响启动。
     * 连接管理（建客户端/惰性建连/退避/自检调度）已收拢到 {@link RedisConnectionFacade}。
     */
    @Bean
    public io.agentmanager.framework.service.RedisEventLog redisEventLog(
            io.agentmanager.framework.redis.RedisConnectionFacade redisConnectionFacade,
            AgentRedisProperties redis) {
        return new io.agentmanager.framework.service.RedisEventLog(redisConnectionFacade, redis);
    }

    @Bean
    public io.agentmanager.framework.service.SessionEventStore sessionEventStore(
            io.agentmanager.framework.service.RedisEventLog redisEventLog,
            AgentManagerProperties props) {
        var cleanup = props.cleanup();
        // 同一份 7 天：这里算 TTL，不再有第二个来源（旧的 deleteBefore 已随 TTL 一并下线）
        var retention = cleanup != null ? cleanup.sessionRetentionDays() : 7;
        return new io.agentmanager.framework.service.SessionEventStore(redisEventLog, retention);
    }

    @Bean
    public io.agentmanager.framework.service.SessionEventBus sessionEventBus(
            io.agentmanager.framework.service.SessionEventStore sessionEventStore,
            AgentManagerProperties props) {
        var sse = props.sse();
        var heartbeat = sse != null ? java.time.Duration.ofSeconds(sse.heartbeatSeconds()) : java.time.Duration.ofSeconds(20);
        var eviction = sse != null ? java.time.Duration.ofMinutes(sse.sinksEvictionMinutes()) : java.time.Duration.ofMinutes(5);
        var bufSize = sse != null ? sse.sinksBufferSize() : 256;
        return new io.agentmanager.framework.service.SessionEventBus(sessionEventStore, heartbeat, eviction, bufSize);
    }

    /**
     * 会话事件追赶器（durable-sse-multinode-plan §2.4）：观察者路径的实现，
     * 只读 Pod 间共享的存储（session_event 在 Redis；turn_lease / confirm_context 仍在 MySQL），
     * 用于被订阅的 session 执行在另一副本上的场景。轮询间隔由
     * AGENT_SSE_TAIL_POLL_MS 控制（默认 300ms）。
     */
    @Bean
    public io.agentmanager.framework.service.SessionEventTailer sessionEventTailer(
            io.agentmanager.framework.service.SessionEventStore sessionEventStore,
            io.agentmanager.framework.service.TurnLeaseStore turnLeaseStore,
            io.agentmanager.framework.service.AgentRuntimeService agentRuntimeService,
            io.agentmanager.framework.service.TurnIntentStore turnIntentStore,
            AgentManagerProperties props) {
        var sse = props.sse();
        var poll = sse != null ? java.time.Duration.ofMillis(sse.tailPollMs())
                               : java.time.Duration.ofMillis(300);
        // 与 EventBus 共用 heartbeatSeconds：两条路径面对的入口代理超时是同一个
        var heartbeat = sse != null ? java.time.Duration.ofSeconds(sse.heartbeatSeconds())
                                    : java.time.Duration.ofSeconds(20);
        return new io.agentmanager.framework.service.SessionEventTailer(
            sessionEventStore, turnLeaseStore, agentRuntimeService, poll, heartbeat, turnIntentStore);
    }

    @Bean
    public AgentRuntimeService agentRuntimeService(
        OafConfig oafConfig,
        HarnessAgent harnessAgent,
        List<Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger,
        io.agentmanager.framework.service.ConfirmContextStore confirmContextStore,
        io.agentmanager.framework.service.AgentStateReader agentStateReader,
        org.springframework.beans.factory.ObjectProvider<SandboxRuntime> sandboxRuntimeProvider,
        HarnessAgentFactory harnessAgentFactory,
        DistributedStore distributedStore,
        UiContextStore uiContextStore,
        SessionUserStore sessionUserStore,
        io.agentmanager.framework.service.ModelCatalog modelCatalog,
        org.springframework.beans.factory.ObjectProvider<OpenSandboxFilesystemSpec> sandboxSpecProvider
    ) {
        var sandboxRuntime = sandboxRuntimeProvider.getIfAvailable();
        boolean isolation = sandboxRuntime != null && sandboxRuntime.sessionAgentIsolationEnabled();
        var service = isolation
            ? new AgentRuntimeService(oafConfig, harnessAgent, mcpConfigs, llmLogger, confirmContextStore,
                true, sandboxRuntime.sessionAgentCacheMax(), sandboxRuntime.sessionAgentCacheTtlMinutes())
            : new AgentRuntimeService(oafConfig, harnessAgent, mcpConfigs, llmLogger, confirmContextStore);
        // HITL 恢复优先走 AgentState（无 TTL）：官方 SDK 把 ASKING 工具连同 replyId 持久化在 state 里
        service.setAgentStateReader(agentStateReader);
        if (isolation) {
            // 会话级工厂与 OafReloadService 的 B 路径同参（build 入参完全一致）；
            // reload 时 OafReloadService 会更新此工厂并清缓存（世代失效）
            service.setSessionAgentFactory(() -> harnessAgentFactory.build(oafConfig,
                distributedStore, llmLogger, uiContextStore, sessionUserStore, modelCatalog,
                sandboxSpecProvider.getIfAvailable()));
            log.info("Per-session agent isolation enabled (cacheMax={}, ttlMinutes={}m)",
                sandboxRuntime.sessionAgentCacheMax(), sandboxRuntime.sessionAgentCacheTtlMinutes());
        }
        return service;
    }
}
