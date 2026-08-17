package io.agentmanager.framework.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

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
     * 沙箱文件系统（SANDBOX_ENABLED=true 时装配）。
     * 未启用时返回 null（@Bean 返回 null = 不注册），harnessAgent 走默认 RemoteFilesystemSpec。
     * 注意：不用 @ConditionalOnProperty——环境变量 SANDBOX_ENABLED 绑定为 sandbox.enabled，
     * 与 agent.sandbox.enabled 键不一致会导致条件误判，方法内判断 config.enabled() 更可靠。
     */
    @Bean
    public OpenSandboxFilesystemSpec sandboxFilesystemSpec(
        SandboxConfig config,
        io.agentmanager.framework.service.WorkspaceReader workspaceReader,
        WorkspaceSyncService workspaceSyncService
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
            .isolationScope(IsolationScope.USER);
        // 请求级 userId 注入：middleware 与 acquire 同一订阅链，顺序执行
        spec.setUserKeyMiddleware(new io.agentmanager.framework.sandbox.opensandbox.SandboxUserKeyMiddleware(spec));
        return spec;
    }

    @Bean
    public WorkspaceSyncService workspaceSyncService(DistributedStore distributedStore) {
        return new WorkspaceSyncService(distributedStore.baseStore());
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
        var ds = new HikariDataSource();
        ds.setJdbcUrl(cp.jdbcUrl());
        ds.setUsername(cp.username());
        ds.setPassword(cp.password());
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        ds.setMaxLifetime(1800000);
        return ds;
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
        var store = DistributedStore.builder()
            .agentStateStore(new io.agentmanager.framework.sandbox.opensandbox.SandboxAwareMysqlAgentStateStore(
                dataSource, dbName, "agent_state", true))
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

    /**
     * 自定义工具集合：在此注册 @Tool 注解的工具类。
     * HarnessAgent 创建时会注册到 Toolkit。
     * 使用特定类型 List 避免收集全部 Bean 造成循环依赖。
     */
    @Bean
    public List<io.agentmanager.framework.tool.BusinessTools> customTools(
        io.agentmanager.framework.tool.BusinessTools businessTools
    ) {
        return List.of(businessTools);
    }

    @Bean
    public HarnessAgent harnessAgent(
        AgentManagerProperties props,
        DistributedStore distributedStore,
        OafConfig oafConfig,
        WorkspaceInitializer workspaceInitializer,
        McpToolRegistrar mcpToolRegistrar,
        List<io.agentmanager.framework.tool.BusinessTools> customTools,
        LLMLogger llmLogger,
        @Autowired(required = false) OpenSandboxFilesystemSpec sandboxSpec
    ) {
        var llm = props.llm();

        try {
            var workspacePath = workspaceInitializer.initialize(
                Path.of(props.configDir()), oafConfig);

            var model = io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
                .apiKey(llm.apiKey())
                .modelName(llm.modelId())
                .baseUrl(llm.baseUrl())
                .build();

            // P1: 包装 memory/compaction 内部 LLM 调用追踪
            // 不设置 .model() 时 harness 回退使用主 model（无 trace），设置包装后行为不变且带 span
            var memoryModel = new io.agentmanager.framework.service.TracingModelWrapper(model, "memory");
            var compactionModel = new io.agentmanager.framework.service.TracingModelWrapper(model, "compaction");

            // 自定义 Toolkit：注册自定义工具 + MCP 工具（Harness 工具由框架自动注册）
            var toolkit = new io.agentscope.core.tool.Toolkit();
            for (var tool : customTools) {
                toolkit.registerTool(tool);
                log.info("Custom tool registered: {}", tool.getClass().getSimpleName());
            }
            mcpToolRegistrar.registerAll(toolkit, oafConfig);

            var builder = HarnessAgent.builder()
                .name(oafConfig.name())
                .sysPrompt(oafConfig.systemPrompt())
                .model(model)
                .toolkit(toolkit)
                // OTel 链路追踪（SDK 内置，创建 span，order=1 默认值）
                .middleware(new io.agentscope.core.tracing.OtelTracingMiddleware())
                // 框架级属性补充（userId/sessionId/tenant，order=0，覆盖 onAgent/onModelCall/onActing）
                .middleware(new io.agentmanager.framework.service.FrameworkTracingMiddleware(oafConfig.slug()))
                // ReAct 推理轮次 span（order=0，覆盖 onReasoning）
                .middleware(new io.agentmanager.framework.service.ReasoningTracingMiddleware())
                // LLM 调用记录（debug 页面，order=1，默认值，保留）
                .middleware(new LlmLoggingMiddleware(llmLogger))
                .workspace(workspacePath)
                .distributedStore(distributedStore);

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

            var agent = builder
                // 记忆管理
                .memory(MemoryConfig.builder()
                    .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                    .consolidationMaxTokens(8_000)
                    .consolidationMinGap(Duration.ofHours(1))
                    .model(memoryModel)            // ← 新增：包装后的 model（flush + consolidation LLM 调用 span）
                    .build())
                // 上下文压缩
                .compaction(CompactionConfig.builder()
                    .triggerMessages(30)
                    .keepMessages(10)
                    .flushBeforeCompact(true)
                    .offloadBeforeCompact(true)
                    .model(compactionModel)        // ← 新增：包装后的 model（compaction LLM 调用 span）
                    .build())
                // 大工具结果卸载
                .toolResultEviction(ToolResultEvictionConfig.defaults())
                // Plan Mode
                .enablePlanMode()
                // 技能自学习
                .enableSkillManageTool(true)
                .build();

            log.info("HarnessAgent created: {} (model: {}, workspace: {})",
                oafConfig.name(), llm.modelId(), workspacePath);
            return agent;
        } catch (Exception e) {
            log.error("Failed to create AgentScope agent: {}", e.getMessage(), e);
            throw new RuntimeException("Agent creation failed", e);
        }
    }

    @Bean
    public io.agentmanager.framework.service.SessionEventBus sessionEventBus() {
        return new io.agentmanager.framework.service.SessionEventBus();
    }

    @Bean
    public AgentRuntimeService agentRuntimeService(
        OafConfig oafConfig,
        HarnessAgent harnessAgent,
        List<Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger
    ) {
        return new AgentRuntimeService(oafConfig, harnessAgent, mcpConfigs, llmLogger);
    }
}
