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
        io.agentmanager.framework.service.storage.FileStorage fileStorage,
        SessionUserStore sessionUserStore,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.redis.RedisConnectionFacade> redisFacadeProvider,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.sandbox.opensandbox.SandboxPoolManager> sandboxPoolProvider
    ) {
        if (!sandboxRuntime.enabled()) {
            log.info("Sandbox disabled (SandboxRuntime), using RemoteFilesystemSpec mode");
            return null;
        }
        log.info("Sandbox enabled, assembling OpenSandboxFilesystemSpec (server={}, image={}, pool={})",
            config.opensandbox().serverUrl(), config.image(), sandboxRuntime.poolEnabled() ? "on" : "off");
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
            // D19 资源双档（sandbox-pool-design）：Pod requests 透传（独立于池开关生效，
            // 非池路径同样低 request 启动；limit 沿用上方 cpuCount/memoryMb）
            .resourceRequests(Map.of(
                "cpu", config.cpuRequest(),
                "memory", config.memoryRequest()
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
        // 沙箱池（sandbox-pool-design D1，SANDBOX_POOL_ENABLED，默认 false）：池管理器经 spec
        // 注入 OpenSandboxClient——create 借出/resume 续约/delete 路由进池；null = 现状路径
        var sandboxPool = sandboxPoolProvider.getIfAvailable();
        if (sandboxPool != null) {
            spec.sandboxPoolManager(sandboxPool);
        }
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
        if (sandboxRuntime.guardEnabled()) {
            var redisFacade = redisFacadeProvider.getIfAvailable();
            if (redisFacade != null) {
                var sessionGranular = sandboxRuntime.sessionAgentIsolationEnabled();
                var guard = new io.agentmanager.framework.sandbox.opensandbox.RedisSandboxExecutionGuard(
                    redisFacade, sandboxRuntime.guardLeaseSeconds() * 1000L, sessionUserStore, sessionGranular);
                // 池化借出身份捕获（sandbox-pool-design P1/P2）：仅池开启时注入——守卫 tryEnter 与
                // SDK acquire P4 借出同线程直调，其 ThreadLocal 写入是唯一落在正确线程的注入点
                // （控制器侧注入会被 HarnessGateway defer+boundedElastic 挪到错误线程，全链路失效）
                if (sandboxPool != null) {
                    guard.setPoolIdentitySpec(spec);
                    log.info("Sandbox pool identity hook attached to SandboxExecutionGuard (borrow identity capture)");
                }
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
        return spec;
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
        return io.agentmanager.framework.redis.RedisConnectionFacade.create(redis);
    }

    /**
     * 沙箱池管理器（sandbox-pool-design §5，SANDBOX_POOL_ENABLED，默认 false）。
     * 关闭（或沙箱未启用 / Redis 门面缺失）返回 null（NullBean）——池消费方
     * （spec/OpenSandboxClient/ConfirmController）按 null 判定走现状路径。
     * D15 不变量（idleReturnSeconds ≥ 确认截止）在管理器构造器 fail-fast 校验，
     * 违反配置直接启动失败。
     *
     * <p>D17 归还兜底回写钩子（RecycleStopper）：扫描转粘滞前幂等 {@code sandbox.stop()}
     * （KV/记忆回写，覆盖崩溃 turn 未经 stop 的容器）。依赖经 ObjectProvider 惰性解析
     * （回收时才取 bean——装配期零循环依赖），任一缺失 → 不回写直接转粘滞（fail-soft，
     * 容器数据保留到粘滞窗口）。每容器每次 recycle 多一次 connect RTT（仅扫描路径，
     * 频度受 idleReturn 约束，可接受）。
     */
    @Bean
    public io.agentmanager.framework.sandbox.opensandbox.SandboxPoolManager sandboxPoolManager(
        SandboxConfig sandboxConfig,
        io.agentmanager.framework.service.SandboxRuntime sandboxRuntime,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.redis.RedisConnectionFacade> redisFacadeProvider,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.service.TurnLeaseStore> turnLeaseStoreProvider,
        org.springframework.beans.factory.ObjectProvider<AgentManagerProperties> propsProvider,
        org.springframework.beans.factory.ObjectProvider<OpenSandboxFilesystemSpec> fsSpecProvider,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.service.WorkspaceReader> workspaceReaderProvider,
        org.springframework.beans.factory.ObjectProvider<WorkspaceSyncService> workspaceSyncServiceProvider,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.service.FileAssetStore> fileAssetStoreProvider,
        org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.service.storage.FileStorage> fileStorageProvider
    ) {
        if (!sandboxRuntime.enabled() || !sandboxRuntime.poolEnabled()) {
            log.info("Sandbox pool disabled (SANDBOX_POOL_ENABLED=false) — per-session containers as before");
            return null;
        }
        var facade = redisFacadeProvider.getIfAvailable();
        if (facade == null) {
            log.warn("Sandbox pool requested (SANDBOX_POOL_ENABLED=true) but no RedisConnectionFacade bean — pool disabled");
            return null;
        }
        var props = propsProvider.getIfAvailable();
        int deadlineMinutes = props != null && props.cleanup() != null
            ? props.cleanup().confirmDeadlineMinutes() : 10;
        // 归还兜底回写（D17 §5.3-4b）：用与 OpenSandboxClient 相同的 beans 构造临时 OpenSandbox
        // 调 stop()（幂等 KV/记忆回写）。依赖在 stop() 调用时经 ObjectProvider 惰性解析——
        // 装配期解析会触发 sandboxFilesystemSpec 创建，与 spec 的 pool ObjectProvider 形成
        // 循环依赖（sandboxFilesystemSpec ⇄ sandboxPoolManager，2026-11-06 实测启动即崩）；
        // 任一依赖缺失 → 本次回写跳过（fail-soft，容器数据保留到粘滞窗口）
        io.agentmanager.framework.sandbox.opensandbox.SandboxPoolManager.RecycleStopper recycleStopper =
            (sandboxId, userKey) -> {
                var reader = workspaceReaderProvider.getIfAvailable();
                var sync = workspaceSyncServiceProvider.getIfAvailable();
                if (reader == null || sync == null) {
                    log.warn("[sandbox-pool] recycle KV sync-back skipped (workspace beans unavailable): sandboxId={}",
                        sandboxId);
                    return;
                }
                var assetStore = fileAssetStoreProvider.getIfAvailable();
                var storage = fileStorageProvider.getIfAvailable();
                var fsSpec = fsSpecProvider.getIfAvailable();
                var osb = com.alibaba.opensandbox.sandbox.Sandbox.connector()
                    .sandboxId(sandboxId)
                    .connectionConfig(com.alibaba.opensandbox.sandbox.config.ConnectionConfig.builder()
                        .domain(sandboxConfig.opensandbox().serverUrl())
                        .apiKey(sandboxConfig.opensandbox().apiKey())
                        .protocol("http")
                        .useServerProxy(true)
                        .build())
                    .skipHealthCheck(true)
                    .connect();
                try {
                    var st = new io.agentmanager.framework.sandbox.opensandbox.OpenSandboxState();
                    st.setSandboxId(sandboxId);
                    var ws = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
                    ws.setRoot("/workspace");
                    st.setWorkspaceSpec(ws);
                    var opts = new io.agentmanager.framework.sandbox.opensandbox.OpenSandboxClientOptions()
                        .serverUrl(sandboxConfig.opensandbox().serverUrl())
                        .apiKey(sandboxConfig.opensandbox().apiKey())
                        .image(sandboxConfig.image())
                        .timeout(Duration.ofMinutes(sandboxConfig.timeoutMinutes()))
                        .entrypoint(sandboxConfig.entrypoint())
                        .resource(Map.of("cpu", String.valueOf(sandboxConfig.cpuCount()),
                            "memory", sandboxConfig.memoryMb() + "Mi"))
                        .resourceRequests(Map.of("cpu", sandboxConfig.cpuRequest(),
                            "memory", sandboxConfig.memoryRequest()))
                        .environment(Map.of("EXECD_API_GRACE_SHUTDOWN",
                            sandboxConfig.execdGraceShutdown().toMillis() + "ms"));
                    var tmp = new io.agentmanager.framework.sandbox.opensandbox.OpenSandbox(
                        st, osb, opts, reader, sync, fsSpec, assetStore, storage);
                    tmp.setUserKey(userKey);
                    tmp.stop();
                } finally {
                    try {
                        osb.close();
                    } catch (Exception ignore) {
                        // 句柄卫生关闭
                    }
                }
            };
        var poolManager = new io.agentmanager.framework.sandbox.opensandbox.SandboxPoolManager(
            facade, sandboxConfig.pool(), deadlineMinutes, sandboxConfig,
            turnLeaseStoreProvider.getIfAvailable(), recycleStopper);
        // 扫描线程 + 预热显式启动（后台 daemon 线程，不阻塞装配）——池关闭分支返回 null 时不注册
        // ApplicationListener（NullBean 强转会崩所有业务服务，见 SandboxPoolManager.start javadoc）
        poolManager.start();
        return poolManager;
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
        org.springframework.beans.factory.ObjectProvider<OpenSandboxFilesystemSpec> sandboxSpecProvider,
        org.springframework.beans.factory.ObjectProvider<AgentManagerProperties> propsProvider
    ) {
        var sandboxRuntime = sandboxRuntimeProvider.getIfAvailable();
        boolean isolation = sandboxRuntime != null && sandboxRuntime.sessionAgentIsolationEnabled();
        var service = isolation
            ? new AgentRuntimeService(oafConfig, harnessAgent, mcpConfigs, llmLogger, confirmContextStore,
                true, sandboxRuntime.sessionAgentCacheMax(), sandboxRuntime.sessionAgentCacheTtlMinutes())
            : new AgentRuntimeService(oafConfig, harnessAgent, mcpConfigs, llmLogger, confirmContextStore);
        // HITL 恢复优先走 AgentState（无 TTL）：官方 SDK 把 ASKING 工具连同 replyId 持久化在 state 里
        service.setAgentStateReader(agentStateReader);
        // D16 确认硬截止（AGENT_CONFIRM_DEADLINE_MINUTES，默认 10；≤0 关闭）：
        // 挂起超截止后确认 410 作废 + 自动按拒绝收尾，迟到消息懒收尾放行（ConfirmDeadlineService）
        var props = propsProvider.getIfAvailable();
        if (props != null && props.cleanup() != null) {
            service.setConfirmDeadlineMinutes(props.cleanup().confirmDeadlineMinutes());
            // SSE permission_ask 帧同步透出 expires_at（前端确认卡倒计时数据源）
            io.agentmanager.framework.controller.AgentEventSseSerializer
                .configureConfirmDeadline(props.cleanup().confirmDeadlineMinutes());
        }
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
