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

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;

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
        org.springframework.beans.factory.ObjectProvider<RedisClient> redisClientProvider
    ) {
        if (!sandboxRuntime.enabled()) {
            log.info("Sandbox disabled (SandboxRuntime), using RemoteFilesystemSpec mode");
            return null;
        }
        log.info("Sandbox enabled, assembling OpenSandboxFilesystemSpec (server={}, image={})",
            config.opensandbox().serverUrl(), config.image());
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
        // 工作区投影开关（issue #27 缓解项）：SANDBOX_PROJECTION_ENABLED=false 时 sandbox start
        // 不再 hydrate 投影目录，降低每轮对话的沙箱同步开销（skills 依赖强的包不要关）
        spec.workspaceProjectionEnabled(sandboxRuntime.projectionEnabled());
        // 并发执行守卫（issue #27c，官方 §9 对 USER 范围的建议）：Redis SET NX 串行化同 userId
        // 的沙箱获取，消解并发 hydrate 互踩与端口竞态的触发面；Redis 不可用时守卫内部 fail-open
        if (sandboxRuntime.guardEnabled()) {
            var redisClient = redisClientProvider.getIfAvailable();
            if (redisClient != null) {
                spec.executionGuard(new io.agentmanager.framework.sandbox.opensandbox.RedisSandboxExecutionGuard(
                    redisClient, "sbx:guard", sandboxRuntime.guardLeaseSeconds() * 1000L));
                log.info("SandboxExecutionGuard enabled (scope=USER serialized, leaseTtl={}s)",
                    sandboxRuntime.guardLeaseSeconds());
            } else {
                log.warn("SandboxExecutionGuard requested (SANDBOX_GUARD_ENABLED) but no RedisClient bean — running unguarded");
            }
        }
        // 请求级 userId 注入：middleware 与 acquire 同一订阅链，顺序执行
        spec.setUserKeyMiddleware(new io.agentmanager.framework.sandbox.opensandbox.SandboxUserKeyMiddleware(spec));
        return spec;
    }

    /**
     * 沙箱回写服务：依赖 WorkspaceReader（而非裸 BaseStore），保证回写命名空间与读取侧一致
     * ——{@code agents/{agent}/users/{uid}}（运行时文件）与
     * {@code agents/{agent}/users/{uid}/skills}（用户技能 L4，key={@code /{技能名}/{路径}}）。
     */
    @Bean
    public WorkspaceSyncService workspaceSyncService(
            io.agentmanager.framework.service.WorkspaceReader workspaceReader) {
        return new WorkspaceSyncService(workspaceReader);
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
     * Redis 客户端（session_event 事件流存储，见 docs/api-frontend-sse.md §12）。
     *
     * <p><b>这里只建客户端，不建连接。</b>Lettuce 是懒连接的，所以 Redis 不可达**不会**让启动失败；
     * 真正的连接（以及随之而来的启动自检）发生在 {@code RedisEventLog} 首次使用时。这是刻意的：
     * Redis 的一次滚动重启不能变成整个 agent 集群的崩溃循环。
     *
     * <p>与「不可达」相对的是「配置错」：URL 非法时 {@code RedisURI.create} 抛
     * {@link IllegalArgumentException}，bean 创建失败 → 启动失败。那属于打包/发布错误，
     * 早失败早发现，两者必须区别对待。
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(AgentRedisProperties redis) {
        var options = ClientOptions.builder()
            .socketOptions(SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(redis.connectTimeoutMs()))
                .build())
            // 必须显式开命令超时：Lettuce 的 TimeoutOptions.DEFAULT_TIMEOUT_COMMANDS 是 false
            // （命令超时默认关闭），而 RedisURI.DEFAULT_TIMEOUT 是 60 秒（javap 核对 6.3.2）。
            // 本应用是 servlet/Tomcat，线程池有限——不钳住就是一条命令占住一个请求线程 60 秒。
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(redis.commandTimeoutMs())))
            .autoReconnect(true)
            // 断线期间**拒绝**命令而不是缓冲：agent 线程要立刻拿到失败（append 返回 -1、回放报错），
            // 而不是阻塞到重连成功。缓冲还会在重连后一次性灌出一批陈旧写入。
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build();
        var uri = RedisURI.create(redis.url());
        var client = RedisClient.create(uri);
        // 必须在首次 connect 之前设置
        client.setOptions(options);
        // 只打 host/port/db，**不打原始 URL**——URL 里可能带密码
        log.info("RedisClient configured (host={}:{}, db={}, commandTimeout={}ms, connectTimeout={}ms, maxLenPerStream={})",
            uri.getHost(), uri.getPort(), uri.getDatabase(),
            redis.commandTimeoutMs(), redis.connectTimeoutMs(), redis.maxLenPerStream());
        return client;
    }

    /**
     * 分布式存储：agent_state 表 (AgentState) + agent_fs 表 (工作区文件)。
     * 使用自定义库名/表名与现有基础设施保持一致。
     * AgentStateStore 使用 SandboxAwareMysqlAgentStateStore：官方 MysqlAgentStateStore
     * 拒绝含 "/" 的 ID，而沙箱 slot ID 形如 sandbox/user/{agentId}/{userId（框架固定格式），
     * 需放宽校验以支持沙箱状态持久化。
     */
    @Bean
    public DistributedStore distributedStore(DataSource dataSource, AgentManagerProperties props) {
        var dbName = props.checkpoint().resolvedDbName();
        // AskingContentBackfillStateStore：持久化前把 ASKING tool_use 块 content 回填为 input 的
        // JSON 字符串——修复 HITL 恢复时 ToolValidator 抛 'argument "content" is null'（写侧治本）
        var store = DistributedStore.builder()
            .agentStateStore(new io.agentmanager.framework.sandbox.opensandbox.AskingContentBackfillStateStore(
                new io.agentmanager.framework.sandbox.opensandbox.SandboxAwareMysqlAgentStateStore(
                    dataSource, dbName, "agent_state", true)))
            .baseStore(JdbcStore.builder(dataSource)
                .tableName("agent_fs")
                .initializeSchema(true)
                .build())
            .build();
        log.info("DistributedStore initialized ({} . agent_state + agent_fs)", dbName);
        return store;
    }

    @Bean
    public io.agentmanager.framework.tool.BusinessTools businessTools() {
        return new io.agentmanager.framework.tool.BusinessTools();
    }

    @Bean
    public io.agentmanager.framework.tool.FileTools fileTools(
        io.agentmanager.framework.service.FileAssetStore fileAssetStore,
        io.agentmanager.framework.service.storage.FileStorage fileStorage,
        io.agentmanager.framework.config.AgentManagerProperties props,
        io.agentmanager.framework.service.SandboxRuntime sandboxRuntime,
        io.agentmanager.framework.service.WorkspaceReader workspaceReader,
        org.springframework.beans.factory.ObjectProvider<OpenSandboxFilesystemSpec> sandboxSpecProvider
    ) {
        return new io.agentmanager.framework.tool.FileTools(fileAssetStore, fileStorage, props,
            sandboxRuntime, workspaceReader, sandboxSpecProvider.getIfAvailable());
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
     * 内置工具运行时注册表（issue #28）：{@code /tools?includeInternal=true} 的唯一事实源。
     * 与 customTools 同源（同一批 @Tool bean），deniedTools 类粒度剔除语义与 harnessAgent
     * 装配一致；经 OafConfigHolder 每请求取值，OAF reload（deniedTools 变更）即时反映。
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
     */
    @Bean
    public io.agentmanager.framework.service.RedisEventLog redisEventLog(
            RedisClient redisClient, AgentRedisProperties redis) {
        return new io.agentmanager.framework.service.RedisEventLog(redisClient, redis);
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
            AgentManagerProperties props) {
        var sse = props.sse();
        var poll = sse != null ? java.time.Duration.ofMillis(sse.tailPollMs())
                               : java.time.Duration.ofMillis(300);
        // 与 EventBus 共用 heartbeatSeconds：两条路径面对的入口代理超时是同一个
        var heartbeat = sse != null ? java.time.Duration.ofSeconds(sse.heartbeatSeconds())
                                    : java.time.Duration.ofSeconds(20);
        return new io.agentmanager.framework.service.SessionEventTailer(
            sessionEventStore, turnLeaseStore, agentRuntimeService, poll, heartbeat);
    }

    @Bean
    public AgentRuntimeService agentRuntimeService(
        OafConfig oafConfig,
        HarnessAgent harnessAgent,
        List<Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger,
        io.agentmanager.framework.service.ConfirmContextStore confirmContextStore,
        io.agentmanager.framework.service.AgentStateReader agentStateReader
    ) {
        var service = new AgentRuntimeService(oafConfig, harnessAgent, mcpConfigs, llmLogger, confirmContextStore);
        // HITL 恢复优先走 AgentState（无 TTL）：官方 SDK 把 ASKING 工具连同 replyId 持久化在 state 里
        service.setAgentStateReader(agentStateReader);
        return service;
    }
}
