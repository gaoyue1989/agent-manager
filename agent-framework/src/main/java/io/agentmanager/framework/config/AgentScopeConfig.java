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
        UiContextStore uiContextStore,
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
                .httpTransport(io.agentscope.core.model.transport.JdkHttpTransport.builder()
                    .client(java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(30))
                        .build())
                    .config(io.agentscope.core.model.transport.HttpTransportConfig.builder()
                        .connectTimeout(java.time.Duration.ofSeconds(30))
                        .readTimeout(java.time.Duration.ofSeconds(180))
                        .writeTimeout(java.time.Duration.ofSeconds(30))
                        .build())
                    .build())
                .build();

            // P1: 包装 memory/compaction 内部 LLM 调用追踪
            // 不设置 .model() 时 harness 回退使用主 model（无 trace），设置包装后行为不变且带 span
            var memoryModel = new io.agentmanager.framework.service.TracingModelWrapper(model, "memory");
            var compactionModel = new io.agentmanager.framework.service.TracingModelWrapper(model, "compaction");

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
                // UI 交互上下文注入（4.7）：PreCall 时按会话 metadata 注入 ui_context（失败不阻断）
                .hook(new UiContextInjectionHook(uiContextStore))
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

            // HITL 权限上下文（MCP-only，未启用时跳过装配保持零侵入）
            if (permissionContext != null) {
                builder.permissionContext(permissionContext);
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

    @Bean
    public AgentRuntimeService agentRuntimeService(
        OafConfig oafConfig,
        HarnessAgent harnessAgent,
        List<Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger,
        io.agentmanager.framework.service.ConfirmContextStore confirmContextStore
    ) {
        return new AgentRuntimeService(oafConfig, harnessAgent, mcpConfigs, llmLogger, confirmContextStore);
    }

    /**
     * 装配 HITL 权限上下文（仅 MCP 工具生效，见 docs/hitl-permission-plan.md 6.1）：
     * 1. 仅当存在 MCP tools 显式规则或 require_confirmation=true 时启用（未配置返回 null，零侵入）
     * 2. 内置 + 自定义工具全量 ALLOW（覆盖 DEFAULT mode 兜底 ASK，保证自带工具不参与确认）
     * 3. MCP 工具：显式规则 + 未声明兜底（require_confirmation → ask，否则 allow）
     *
     * 规则匹配为精确工具名映射（PermissionEngine.rulesFor = map.get(name)，无通配符）。
     * 内置工具名单无法在 build 前运行时枚举（内置工具注册发生在 Builder.build() 内部），
     * 使用 BUILT_IN_TOOL_NAMES 静态白名单（javap 从 jar 提取验证）+ verifyToolCoverage 构建后校验。
     */
    private io.agentscope.core.permission.PermissionContextState buildPermissionContext(
            OafConfig oafConfig,
            McpToolRegistrar.PermissionRuleResult permCfg,
            Set<String> customToolNames) {
        var requireAll = oafConfig.runtimeConfig().requireConfirmation();
        if (permCfg.tools().isEmpty() && !requireAll) {
            return null;
        }

        var pb = io.agentscope.core.permission.PermissionContextState.builder()
            .mode(permCfg.mode());

        // ① 自带工具（内置白名单 + 本次注册的自定义 @Tool）自动放行
        var builtinNames = new HashSet<String>();
        builtinNames.addAll(BUILT_IN_TOOL_NAMES);
        builtinNames.addAll(customToolNames);
        for (var toolName : builtinNames) {
            if (permCfg.mcpNames().contains(toolName)) {
                continue; // 与 MCP 重名时以 MCP 规则为准
            }
            pb.addAllowRule(toolName,
                new io.agentscope.core.permission.PermissionRule(
                    toolName, null,
                    io.agentscope.core.permission.PermissionBehavior.ALLOW, "builtinAutoAllow"));
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
        log.info("Permission system enabled (MCP only): mode={}, tools={}, mcpTools={}",
            permCfg.mode(), permCfg.tools().size(), permCfg.mcpNames().size());
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
