package io.agentmanager.framework.config;

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
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

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
    public SkillManager skillManager(OafConfig oafConfig, AgentManagerProperties props) {
        var configDir = java.nio.file.Path.of(props.configDir());
        return new SkillManager(oafConfig, configDir);
    }

    @Bean
    public List<SkillManager.SkillInfo> loadedSkills(SkillManager skillManager, OafConfig oafConfig) {
        return skillManager.loadAll(oafConfig.localSkills());
    }

    @Bean
    public McpManager mcpManager(AgentManagerProperties props) {
        return new McpManager(java.nio.file.Path.of(props.configDir()));
    }

    @Bean
    public List<java.util.Map<String, Object>> mcpConfigs(McpManager mcpManager, OafConfig oafConfig) {
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

    @Bean
    public io.agentscope.core.state.AgentStateStore agentStateStore(DataSource dataSource) {
        return new MysqlAgentStateStore(dataSource, "agent_manager_test", "agent_state", true);
    }

    @Bean
    public ReActAgent reactAgent(
        AgentManagerProperties props,
        io.agentscope.core.state.AgentStateStore stateStore,
        OafConfig oafConfig
    ) {
        var llm = props.llm();

        try {
            var model = io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
                .apiKey(llm.apiKey())
                .modelName(llm.modelId())
                .baseUrl(llm.baseUrl())
                .build();

            var builder = ReActAgent.builder()
                .name(oafConfig.name())
                .sysPrompt(oafConfig.systemPrompt())
                .model(model)
                .toolkit(new Toolkit())
                .stateStore(stateStore);

            if (!oafConfig.tools().isEmpty()) {
                var tk = new Toolkit();
                for (var toolName : oafConfig.tools()) {
                    registerBuiltinTool(tk, toolName);
                }
                builder.toolkit(tk);
            }

            var agent = builder.build();
            log.info("Agent created: {} (model: {})", oafConfig.name(), llm.modelId());
            return agent;
        } catch (Exception e) {
            log.error("Failed to create AgentScope agent: {}", e.getMessage(), e);
            throw new RuntimeException("Agent creation failed", e);
        }
    }

    private void registerBuiltinTool(Toolkit tk, String toolName) {
        switch (toolName.toLowerCase()) {
            case "bash", "execute" -> {}
            case "read" -> {}
            case "edit" -> {}
            case "grep" -> {}
            default -> log.debug("Custom tool not yet implemented: {}", toolName);
        }
    }

    @Bean
    public AgentRuntimeService agentRuntimeService(
        OafConfig oafConfig,
        ReActAgent reactAgent,
        List<SkillManager.SkillInfo> loadedSkills,
        List<java.util.Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger
    ) {
        return new AgentRuntimeService(oafConfig, reactAgent, loadedSkills, mcpConfigs, llmLogger);
    }
}
