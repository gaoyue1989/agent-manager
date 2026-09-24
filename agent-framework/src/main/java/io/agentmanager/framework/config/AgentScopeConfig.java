package io.agentmanager.framework.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;

@Configuration
public class AgentScopeConfig {
    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

    /**
     * Harness 内置工具注册名白名单（仅权限系统启用时生效，用于生成自带工具 ALLOW 规则）。
     * 来源：agentscope-harness 2.0.0 jar（javap 提取 @Tool name / AgentTool 实现名），
     * 与当前 builder 开关（enablePlanMode / enableSkillManageTool(true)）对齐。
     * 注意：ShellExecuteTool 的 @Tool 注解无显式 name，注册名取方法名 "execute"
     * （AGENTS.md 记载的 shell_execute 已失效）。
     * 构建后会与实际 getToolNames() 差集校验（verifyToolCoverage），SDK 升级漂移时打 ERROR 日志。
     */
    private static final Set<String> BUILT_IN_TOOL_NAMES = Set.of(
        // 文件系统 (FilesystemTool)
        "read_file", "write_file", "edit_file", "list_files", "glob_files", "grep_files",
        // 记忆 (MemorySearchTool / MemoryGetTool / MemorySaveTool)
        "memory_search", "memory_get", "memory_save",
        // 会话 (SessionSearchTool)
        "session_search", "session_list", "session_history",
        // Shell (ShellExecuteTool，方法名 execute)
        "execute",
        // Plan Mode (PlanModeTools)
        "plan_enter", "plan_write", "plan_exit",
        // 技能 (SkillManageTool / ProposeSkillTool)
        "skill_manage", "propose_skill",
        // 子 Agent (AgentSpawnTool)
        "agent_spawn", "agent_send", "agent_list",
        // 异步任务 (TaskTool / WaitAsyncResultsTool)
        "task_list", "task_output", "task_cancel", "wait_async_results",
        // 动态子 Agent 生成（未启用时不注册，白名单冗余无害）
        "agent_generate"
    );

    /**
     * 沙箱文件系统（SANDBOX_ENABLED=true 时装配）。
     * 未启用时返回 null（@Bean 返回 null = 不注册），harnessAgent 走默认 RemoteFilesystemSpec。
     * 注意：不用 @ConditionalOnProperty——环境变量 SANDBOX_ENABLED 绑定为 sandbox.enabled，
     * 与 agent.sandbox.enabled 键不一致会导致条件误判，方法内判断 config.enabled() 更可靠。
     */
    @Bean
    public OpenSandboxFilesystemSpec sandboxFilesystemSpec(
        SandboxConfig config,
        io.agentmanager.framework.service.WorkspaceReader workspaceReader,
        WorkspaceSyncService workspaceSyncService,
        io.agentmanager.framework.service.FileAssetStore fileAssetStore,
        io.agentmanager.framework.service.storage.FileStorage fileStorage
    ) {
        if (!config.enabled()) {
            log.info("Sandbox disabled (agent.sandbox.enabled=false), using RemoteFilesystemSpec mode");
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
        io.agentmanager.framework.config.SandboxConfig sandboxConfig,
        io.agentmanager.framework.service.WorkspaceReader workspaceReader,
        org.springframework.beans.factory.ObjectProvider<OpenSandboxFilesystemSpec> sandboxSpecProvider
    ) {
        return new io.agentmanager.framework.tool.FileTools(fileAssetStore, fileStorage, props,
            sandboxConfig, workspaceReader, sandboxSpecProvider.getIfAvailable());
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
    @SuppressWarnings("rawtypes")
    public List<Object> customTools(
        io.agentmanager.framework.tool.BusinessTools businessTools,
        io.agentmanager.framework.tool.FileTools fileTools
    ) {
        return java.util.Arrays.asList(businessTools, fileTools);
    }

    io.agentscope.extensions.model.openai.OpenAIChatModel buildChatModel(
        AgentManagerProperties.LLMConfig llm,
        AgentManagerProperties.HarnessConfig harness) {
        var optionsBuilder = io.agentscope.core.model.GenerateOptions.builder()
            .temperature(llm.temperature())
            .maxTokens(llm.maxTokens());
        // Qwen3 / vLLM: enableThinking=false → chat_template_kwargs.enable_thinking=false
        // 关闭深度思考模式，避免响应中包含 <think>...</think> 冗余内容
        if (!llm.enableThinking()) {
            optionsBuilder.additionalBodyParam("chat_template_kwargs",
                java.util.Map.of("enable_thinking", false));
            log.info("Deep thinking disabled: chat_template_kwargs.enable_thinking=false");
        }
        var modelBuilder = io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
            .apiKey(llm.apiKey())
            .modelName(llm.modelId())
            .baseUrl(llm.baseUrl());
        // 模型上下文窗口（LLM_CONTEXT_LENGTH）：> 0 才传入，未配置保持框架默认行为
        if (llm.contextLength() > 0) {
            modelBuilder.contextWindowSize(llm.contextLength());
        }
        return modelBuilder
            .generateOptions(optionsBuilder.build())
            .httpTransport(io.agentscope.core.model.transport.JdkHttpTransport.builder()
                .client(java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(harness.httpConnectTimeoutSeconds()))
                    .build())
                .config(io.agentscope.core.model.transport.HttpTransportConfig.builder()
                    .connectTimeout(Duration.ofSeconds(harness.httpConnectTimeoutSeconds()))
                    .readTimeout(Duration.ofSeconds(harness.httpReadTimeoutSeconds()))
                    .writeTimeout(Duration.ofSeconds(harness.httpWriteTimeoutSeconds()))
                    .build())
                .build())
            .build();
    }

    /**
     * 会话标题生成专用模型：与主对话模型同配置的独立实例，
     * 供 {@link io.agentmanager.framework.service.SessionTitleService} 在会话首条消息后异步生成标题。
     * 不参与 HarnessAgent 装配（独立实例，避免影响主链路追踪/日志包装）。
     */
    @Bean
    public io.agentscope.core.model.Model titleGenerationModel(AgentManagerProperties props) {
        var llm = props.llm();
        var harness = props.harness() != null ? props.harness() : AgentManagerProperties.HarnessConfig.defaults();
        return buildChatModel(llm, harness);
    }

    @Bean
    public HarnessAgent harnessAgent(
        AgentManagerProperties props,
        DistributedStore distributedStore,
        OafConfig oafConfig,
        WorkspaceInitializer workspaceInitializer,
        McpToolRegistrar mcpToolRegistrar,
        @SuppressWarnings("rawtypes") List customTools,
        LLMLogger llmLogger,
        UiContextStore uiContextStore,
        SessionUserStore sessionUserStore,
        @Autowired(required = false) OpenSandboxFilesystemSpec sandboxSpec
    ) {
        var llm = props.llm();
        var harness = props.harness() != null ? props.harness() : AgentManagerProperties.HarnessConfig.defaults();

        try {
            var workspacePath = workspaceInitializer.initialize(
                Path.of(props.resolvedWorkspaceBaseDir()), oafConfig);

            var model = buildChatModel(llm, harness);

            // P0: 包装主 model，400 错误时打印请求体 JSON 诊断（排查 Higress 网关注入问题）
            var loggingModel = new io.agentmanager.framework.service.RequestBodyLoggingModelWrapper(model);

            // P1: 包装 compaction 内部 LLM 调用追踪
            // 不设置 .model() 时 harness 回退使用主 model（无 trace），设置包装后行为不变且带 span
            // （memoryModel 的构造移入下方 memoryEnabled 分支：记忆关闭时不构造）
            var compactionModel = new io.agentmanager.framework.service.TracingModelWrapper(loggingModel, "compaction");

            // 自定义 Toolkit：注册自定义工具 + MCP 工具（Harness 工具由框架自动注册）
            var toolkit = new io.agentscope.core.tool.Toolkit();
            // 可见性控制（5.2）：deniedTools 命中的自定义工具不注册（类粒度，任一 @Tool 命中即整体跳过）
            var customToolNames = new HashSet<String>();
            for (var tool : customTools) {
                var names = toolToolNames(tool);
                if (oafConfig.hasDeniedTools()
                        && names.stream().anyMatch(oafConfig.deniedTools()::contains)) {
                    log.info("Custom tool(s) {} excluded by deniedTools", names);
                    continue;
                }
                toolkit.registerTool(tool);
                customToolNames.addAll(names);
                log.info("Custom tool registered: {}", names);
            }
            mcpToolRegistrar.registerAll(toolkit, oafConfig);

            // HITL 权限上下文装配（MCP-only）：仅 MCP tools 规则或 require_confirmation 存在时启用
            var permCfg = mcpToolRegistrar.collectPermissionRules(oafConfig);
            var permissionContext = buildPermissionContext(oafConfig, permCfg, customToolNames);

            var builder = HarnessAgent.builder()
                .name(oafConfig.name())
                .sysPrompt(oafConfig.systemPrompt())
                .model(loggingModel)
                .toolkit(toolkit)
                // ReAct 推理最大轮次：SDK 默认 10 轮不足以支撑"生成 OAF 部署包"等
                // 长流程（撰写→校验→修正→打包→登记→汇报），默认放宽至 20 轮（AGENT_REACT_MAX_ITERS 可调）
                .maxIters(harness.maxIters())
                // OTel 链路追踪（SDK 内置，创建 span，order=1 默认值）
                .middleware(new io.agentscope.core.tracing.OtelTracingMiddleware())
                // 框架级属性补充（userId/sessionId/tenant，order=0，覆盖 onAgent/onModelCall/onActing）
                .middleware(new io.agentmanager.framework.service.FrameworkTracingMiddleware(oafConfig.slug()))
                // ReAct 推理轮次 span（order=0，覆盖 onReasoning）
                .middleware(new io.agentmanager.framework.service.ReasoningTracingMiddleware())
                // LLM 调用记录（debug 页面，order=1，默认值，保留）
                .middleware(new LlmLoggingMiddleware(llmLogger))
                // ToolUseBlock 完整性校验（vLLM/Qwen3 流式输出畸形 tool call 防御）
                .middleware(new ToolCallValidationMiddleware())
                // MCP 用户上下文注入（唯一注入点）：把生效 userId 写入 McpMeta，
                // 供 MCP 工具调用走 _meta / userHeaders 双通道（Channel 链路按 session 反查真实 userId）
                .middleware(new io.agentmanager.framework.mcp.McpUserContextMiddleware(sessionUserStore))
                // 空完成恢复（思维模型 thinking 耗尽 max_tokens 后只产出 ThinkingBlock 无实际输出时自动重试）
                .hook(new EmptyCompletionRecoveryHook())
                // UI 交互上下文注入（4.7）：PreCall 时按会话 metadata 注入 ui_context（失败不阻断）
                .hook(new UiContextInjectionHook(uiContextStore))
                .workspace(workspacePath)
                .distributedStore(distributedStore);

            // OAF 包内技能目录注册为市场层（skill 四层优先级 L2）：
            // HarnessSkillMiddleware 每轮推理重扫目录（mtime+size 短路），PVC 上
            // /config/skills 原位变化无需重启即可在下轮生效（动态加载）。
            // writeable=false 只读分发：skill_manage/skill 目录写回被仓库层拒绝（PVC 只读）。
            var oafSkillsDir = Path.of(props.configDir()).resolve("skills");
            if (java.nio.file.Files.isDirectory(oafSkillsDir)) {
                builder.skillRepository(new io.agentscope.core.skill.repository.FileSystemSkillRepository(
                    oafSkillsDir, false, "oaf-package"));
                log.info("OAF skill repository registered (dynamic L2): {}", oafSkillsDir);
            } else {
                log.info("OAF skills dir not found, dynamic skill repository skipped: {}", oafSkillsDir);
            }

            // 沙箱模式：OpenSandboxFilesystemSpec（SANDBOX_ENABLED=true 时注入）
            // 默认模式：RemoteFilesystemSpec（共享存储，不提供 Shell）
            if (sandboxSpec != null) {
                builder.filesystem(sandboxSpec);
                // 请求级 userId 注入：框架内部 exec 不带 RuntimeContext（实测），
                // middleware 在调用链上把 userId 注入沙箱供 stop() 回写
                if (sandboxSpec.getUserKeyMiddleware() != null) {
                    builder.middleware(sandboxSpec.getUserKeyMiddleware());
                }
            } else {
                builder.filesystem(new RemoteFilesystemSpec()
                    .isolationScope(IsolationScope.USER));
            }

            // HITL 权限上下文（MCP-only，未启用时跳过装配保持零侵入）
            if (permissionContext != null) {
                builder.permissionContext(permissionContext);
            }

            // 记忆装配分支（AGENT_MEMORY_ENABLED 可调）：false 时不仅要跳过 .memory(...)，
            // 还须显式关闭记忆 hooks 与 memory_* 工具——只去掉 .memory(...) 不算"完全关闭"，
            // SDK 仍会以内置默认装配记忆钩子/工具（disableMemoryHooks + disableMemoryTools 双关）
            if (harness.memoryEnabled()) {
                // P1: 包装 memory 内部 LLM 调用追踪（flush + consolidation LLM 调用 span）
                var memoryModel = new io.agentmanager.framework.service.TracingModelWrapper(loggingModel, "memory");
                builder
                    // 记忆管理（AGENT_MEMORY_* 可调）
                    .memory(MemoryConfig.builder()
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(
                            Duration.ofMinutes(harness.memoryFlushThrottleMinutes())))
                        .consolidationMaxTokens(harness.memoryConsolidationMaxTokens())
                        .consolidationMinGap(Duration.ofMinutes(harness.memoryConsolidationMinGapMinutes()))
                        .model(memoryModel)            // ← 包装后的 model（flush + consolidation LLM 调用 span）
                        .build());
            } else {
                builder.disableMemoryHooks().disableMemoryTools();
                log.info("Memory fully disabled (agent.harness.memory-enabled=false)");
            }

            var agent = builder
                // 上下文压缩（AGENT_COMPACTION_* 可调）
                .compaction(CompactionConfig.builder()
                    .triggerMessages(harness.compactionTriggerMessages())
                    .keepMessages(harness.compactionKeepMessages())
                    // 记忆总开关关闭时强制不刷写：SDK 2.0.3 的压缩前 flush 走 CompactionMiddleware
                    // 内部自建的 MemoryFlushManager（仅判 CompactionConfig.isFlushBeforeCompact()），
                    // 不经 disableMemoryHooks —— 不在此处置 false，压缩阈值触发仍会发起记忆抽取
                    // LLM 调用并写 MEMORY.md/memory/，违反"完全关闭"
                    .flushBeforeCompact(harness.memoryEnabled() && harness.compactionFlushBeforeCompact())
                    .offloadBeforeCompact(harness.compactionOffloadBeforeCompact())
                    .model(compactionModel)        // ← 新增：包装后的 model（compaction LLM 调用 span）
                    .build())
                // 大工具结果卸载
                .toolResultEviction(ToolResultEvictionConfig.defaults())
                // Plan Mode
                .enablePlanMode()
                // 技能自学习
                .enableSkillManageTool(true)
                .build();

            // 权限覆盖校验（仅启用权限系统时）：内置白名单 vs 实际注册集，SDK 升级漂移时 ERROR 提示
            if (permissionContext != null) {
                verifyToolCoverage(agent, oafConfig, customToolNames, permCfg.mcpNames());
            }

            log.info("HarnessAgent created: {} (model: {}, workspace: {})",
                oafConfig.name(), llm.modelId(), workspacePath);
            return agent;
        } catch (Exception e) {
            log.error("Failed to create AgentScope agent: {}", e.getMessage(), e);
            throw new RuntimeException("Agent creation failed", e);
        }
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

    /**
     * 装配 HITL 权限上下文（MCP + 自定义/内置工具，见 docs/hitl-permission-plan.md 6.1）：
     * 1. 仅当存在 MCP tools 显式规则、require_confirmation=true 或 frontmatter
     *    config.permission.tools 声明时启用（未配置返回 null，零侵入）
     * 2. 自定义/内置工具：frontmatter 显式声明走对应 allow/ask/deny 规则；
     *    未声明默认 ALLOW（覆盖 DEFAULT mode 兜底 ASK，保持既有行为）
     * 3. MCP 工具：显式规则 + 未声明兜底（require_confirmation → ask，否则 allow）；
     *    与 MCP 裸名冲突时以 MCP 规则为准
     *
     * 规则匹配为精确工具名映射（PermissionEngine.rulesFor = map.get(name)，无通配符）。
     * 内置工具名单无法在 build 前运行时枚举（内置工具注册发生在 Builder.build() 内部），
     * 使用 BUILT_IN_TOOL_NAMES 静态白名单（javap 从 jar 提取验证）+ verifyToolCoverage 构建后校验。
     *
     * <p>package-private：便于单元测试断言规则装配结果。
     */
    io.agentscope.core.permission.PermissionContextState buildPermissionContext(
            OafConfig oafConfig,
            McpToolRegistrar.PermissionRuleResult permCfg,
            Set<String> customToolNames) {
        var requireAll = oafConfig.runtimeConfig().requireConfirmation();
        var customRules = oafConfig.runtimeConfig().permissionTools();
        var hasCustomRules = customRules != null && !customRules.isEmpty();
        if (permCfg.tools().isEmpty() && !requireAll && !hasCustomRules) {
            return null;
        }

        var pb = io.agentscope.core.permission.PermissionContextState.builder()
            .mode(permCfg.mode());

        // ① 自带工具（内置白名单 + 本次注册的自定义 @Tool）：显式声明优先，未声明自动放行
        var builtinNames = new HashSet<String>();
        builtinNames.addAll(BUILT_IN_TOOL_NAMES);
        builtinNames.addAll(customToolNames);
        for (var toolName : builtinNames) {
            if (permCfg.mcpNames().contains(toolName)) {
                continue; // 与 MCP 重名时以 MCP 规则为准
            }
            var declared = hasCustomRules ? customRules.get(toolName) : null;
            if (declared == null) {
                pb.addAllowRule(toolName,
                    new io.agentscope.core.permission.PermissionRule(
                        toolName, null,
                        io.agentscope.core.permission.PermissionBehavior.ALLOW, "builtinAutoAllow"));
                continue;
            }
            var rule = new io.agentscope.core.permission.PermissionRule(
                toolName, null,
                io.agentscope.core.permission.PermissionBehavior.valueOf(declared.toUpperCase()),
                "frontmatter");
            switch (declared) {
                case "allow" -> pb.addAllowRule(toolName, rule);
                case "ask" -> pb.addAskRule(toolName, rule);
                case "deny" -> pb.addDenyRule(toolName, rule);
            }
        }

        // ② MCP 工具：显式规则 + 兜底（未声明：require_confirmation=true → ask，否则 allow）
        for (var name : permCfg.mcpNames()) {
            var behavior = permCfg.tools().getOrDefault(name, requireAll ? "ask" : "allow");
            var rule = new io.agentscope.core.permission.PermissionRule(
                name, null,
                io.agentscope.core.permission.PermissionBehavior.valueOf(behavior.toUpperCase()),
                "projectSettings");
            switch (behavior) {
                case "allow" -> pb.addAllowRule(name, rule);
                case "ask" -> pb.addAskRule(name, rule);
                case "deny" -> pb.addDenyRule(name, rule);
            }
        }

        // ③ 声明了未注册工具（deniedTools 排除/名字写错）→ 告警忽略，规则不生效
        if (hasCustomRules) {
            var known = new HashSet<String>();
            known.addAll(builtinNames);
            known.addAll(permCfg.mcpNames());
            for (var name : customRules.keySet()) {
                if (!known.contains(name)) {
                    log.warn("config.permission.tools declares '{}' but no such tool is registered "
                        + "(deniedTools filtered or typo), rule ignored", name);
                }
            }
        }

        log.info("Permission system enabled (MCP + custom): mode={}, mcpRules={}, customRules={}",
            permCfg.mode(), permCfg.tools().size(), hasCustomRules ? customRules.size() : 0);
        return pb.build();
    }

    /** 反射提取 @Tool 注册名集合（注解无 name 时取方法名，与 Toolkit.registerTool 派生规则一致） */
    private static Set<String> toolToolNames(Object tool) {
        var names = new LinkedHashSet<String>();
        for (var method : tool.getClass().getMethods()) {
            var ann = method.getAnnotation(io.agentscope.core.tool.Tool.class);
            if (ann != null) {
                names.add(ann.name().isBlank() ? method.getName() : ann.name());
            }
        }
        return names;
    }

    /**
     * 构建后校验权限覆盖：实际注册工具集 vs 白名单（内置 + 自定义 + MCP）。
     * 未覆盖工具在 DEFAULT mode 下会触发 ASK（自带工具应放行）——SDK 升级、
     * builder 开关变化导致内置名漂移时打 ERROR 日志提示更新 BUILT_IN_TOOL_NAMES。
     * deniedTools 由 Harness tools.json 侧隐藏，不计入风险。
     */
    private void verifyToolCoverage(HarnessAgent agent, OafConfig oafConfig,
                                    Set<String> customToolNames, Set<String> mcpNames) {
        var covered = new HashSet<String>();
        covered.addAll(BUILT_IN_TOOL_NAMES);
        covered.addAll(customToolNames);
        covered.addAll(mcpNames);

        var actual = new TreeSet<>(agent.getToolkit().getToolNames());
        var uncovered = new TreeSet<>(actual);
        uncovered.removeAll(covered);
        if (oafConfig.deniedTools() != null) {
            uncovered.removeAll(oafConfig.deniedTools());
        }
        if (!uncovered.isEmpty()) {
            log.error("Permission coverage gap: tools {} are NOT covered by ALLOW/ASK/DENY rules "
                    + "and will trigger ASK in DEFAULT mode. Harness built-in tool names changed after "
                    + "SDK upgrade? Update AgentScopeConfig.BUILT_IN_TOOL_NAMES or declare "
                    + "permissions.tools in mcp-configs/{server}/config.yaml. Actual tools: {}",
                uncovered, actual);
        } else {
            log.info("Permission coverage verified: {} actual tools, {} with rules",
                actual.size(), covered.size());
        }
    }
}
