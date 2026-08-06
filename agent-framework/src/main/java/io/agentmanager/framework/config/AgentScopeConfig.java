package io.agentmanager.framework.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

import io.agentmanager.framework.model.OafConfig;
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
    public McpManager mcpManager(AgentManagerProperties props) {
        return new McpManager(Path.of(props.configDir()));
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
     */
    @Bean
    public DistributedStore distributedStore(DataSource dataSource) {
        var store = DistributedStore.builder()
            .agentStateStore(new MysqlAgentStateStore(
                dataSource, "agent_manager_test", "agent_state", true))
            .baseStore(JdbcStore.builder(dataSource)
                .tableName("agent_fs")
                .initializeSchema(true)
                .build())
            .build();
        log.info("DistributedStore initialized (agent_manager_test.agent_state + agent_fs)");
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
        List<io.agentmanager.framework.tool.BusinessTools> customTools
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

            // 自定义 Toolkit：注册自定义工具 + MCP 工具（Harness 工具由框架自动注册）
            var toolkit = new io.agentscope.core.tool.Toolkit();
            for (var tool : customTools) {
                toolkit.registerTool(tool);
                log.info("Custom tool registered: {}", tool.getClass().getSimpleName());
            }
            mcpToolRegistrar.registerAll(toolkit, oafConfig);

            var agent = HarnessAgent.builder()
                .name(oafConfig.name())
                .sysPrompt(oafConfig.systemPrompt())
                .model(model)
                .toolkit(toolkit)
                .workspace(workspacePath)
                .distributedStore(distributedStore)
                .filesystem(new RemoteFilesystemSpec()
                    .isolationScope(IsolationScope.USER))
                // 记忆管理
                .memory(MemoryConfig.builder()
                    .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                    .consolidationMaxTokens(8_000)
                    .consolidationMinGap(Duration.ofHours(1))
                    .build())
                // 上下文压缩
                .compaction(CompactionConfig.builder()
                    .triggerMessages(30)
                    .keepMessages(10)
                    .flushBeforeCompact(true)
                    .offloadBeforeCompact(true)
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
    public AgentRuntimeService agentRuntimeService(
        OafConfig oafConfig,
        HarnessAgent harnessAgent,
        List<Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger
    ) {
        return new AgentRuntimeService(oafConfig, harnessAgent, mcpConfigs, llmLogger);
    }
}
