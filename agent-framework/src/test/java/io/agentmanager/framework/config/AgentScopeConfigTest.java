package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.A2uiService;
import io.agentmanager.framework.service.McpManager;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.WorkspaceInitializer;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.tool.BusinessTools;
import io.agentscope.harness.agent.DistributedStore;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

class AgentScopeConfigTest {

    private final AgentScopeConfig config = new AgentScopeConfig();

    @Test
    void mcpManagerShouldUseConfigPath() {
        var props = new AgentManagerProperties(emptyLlm(), emptyServer(), emptyCheckpoint(), "/test");
        var mcpRegistrar = mock(McpToolRegistrar.class);

        assertNotNull(config.mcpManager(props, mcpRegistrar));
    }

    @Test
    void mcpConfigsShouldLoadFromManager() {
        var mgr = mock(McpManager.class);
        var oaf = mock(OafConfig.class);
        when(mgr.loadConfigs(anyList())).thenReturn(List.of());
        when(oaf.mcpServers()).thenReturn(List.of());

        assertEquals(List.of(), config.mcpConfigs(mgr, oaf));
    }

    @Test
    void a2uiServiceShouldUseOafCatalogId() {
        var oaf = mock(OafConfig.class);
        when(oaf.getCatalogId()).thenReturn("custom-cat");

        assertNotNull(config.a2uiService(oaf));
    }

    @Test
    void businessToolsAndCustomToolsShouldWork() {
        var tool = config.businessTools();
        assertEquals(List.of(tool), config.customTools(tool));
    }

    @Test
    void dataSourceShouldUseCheckpointProperties() {
        var props = new AgentManagerProperties(
            emptyLlm(), emptyServer(),
            new AgentManagerProperties.CheckpointConfig(
                "jdbc:mysql://localhost:3306/test", "u", "p", "test"),
            "/config");

        var ds = config.dataSource(props);
        assertInstanceOf(HikariDataSource.class, ds);
        assertEquals("jdbc:mysql://localhost:3306/test", ((HikariDataSource) ds).getJdbcUrl());
    }

    @Test
    void resolvedDbNameShouldParseFromJdbcUrlWhenNotConfigured() {
        var cp = new AgentManagerProperties.CheckpointConfig(
            "jdbc:mysql://127.0.0.1:3307/agent_fw_test?useSSL=false", "u", "p", "");
        assertEquals("agent_fw_test", cp.resolvedDbName());
    }

    @Test
    void resolvedDbNameShouldPreferExplicitConfig() {
        var cp = new AgentManagerProperties.CheckpointConfig(
            "jdbc:mysql://127.0.0.1:3307/agent_manager_test", "u", "p", "custom_db");
        assertEquals("custom_db", cp.resolvedDbName());
    }

    @Test
    void resolvedDbNameShouldFallbackWhenUrlHasNoDatabase() {
        var cp = new AgentManagerProperties.CheckpointConfig(
            "jdbc:mysql://127.0.0.1:3307", "u", "p", "");
        assertEquals("agent_manager_test", cp.resolvedDbName());
    }

    @Test
    void harnessAgentShouldThrowWhenWorkspaceInitFails() {
        var props = propsForLlm();
        var oaf = mock(OafConfig.class);
        var ws = mock(WorkspaceInitializer.class);
        var mcp = mock(McpToolRegistrar.class);
        var store = mock(DistributedStore.class);

        when(oaf.name()).thenReturn("test-agent");
        when(oaf.systemPrompt()).thenReturn("prompt");

        var ioe = new java.io.IOException("workspace init failed");
        try {
            when(ws.initialize(any(java.nio.file.Path.class), any(OafConfig.class))).thenThrow(ioe);
        } catch (Exception e) {
            fail(e);
        }

        assertThrows(RuntimeException.class,
            () -> config.harnessAgent(props, store, oaf, ws, mcp,
                List.of(new BusinessTools()), new LLMLogger(), null, null));
    }

    private static AgentManagerProperties propsForLlm() {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig(
                "k", "m", "http://localhost", "openai", 0.7, 4096, 120),
            emptyServer(),
            new AgentManagerProperties.CheckpointConfig(
                "jdbc:mysql://localhost:3306/cp", "u", "p", "cp"),
            "/config");
    }

    private static AgentManagerProperties.LLMConfig emptyLlm() {
        return new AgentManagerProperties.LLMConfig("", "", "", "openai", 0.7, 4096, 120);
    }

    private static AgentManagerProperties.ServerConfig emptyServer() {
        return new AgentManagerProperties.ServerConfig("0.0.0.0", 8100);
    }

    private static AgentManagerProperties.CheckpointConfig emptyCheckpoint() {
        return new AgentManagerProperties.CheckpointConfig(
            "jdbc:mysql://127.0.0.1:3307/agent_manager_test", "u", "p", "agent_manager_test");
    }

    private HikariDataSource dataSourceForTest() {
        var ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://localhost:3306/does-not-matter");
        ds.setUsername("u");
        ds.setPassword("p");
        ds.setMaximumPoolSize(1);
        return ds;
    }
}