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
    private final io.agentmanager.framework.service.HarnessAgentFactory factory =
        new io.agentmanager.framework.service.HarnessAgentFactory(
            propsForLlm(), null, null, java.util.List.of());

    @Test
    void mcpManagerShouldUseConfigPath() {
        var props = new AgentManagerProperties(emptyLlm(), emptyServer(), emptyCheckpoint(), "/test", "",
            cleanupConfig(), emptyFileConfig(), new AgentManagerProperties.SseConfig(20, 5, 256, 300),harnessConfig());
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
        var fileTools = config.fileTools(mock(io.agentmanager.framework.service.FileAssetStore.class),
            mock(io.agentmanager.framework.service.storage.FileStorage.class), propsForLlm(),
            mock(io.agentmanager.framework.service.SandboxRuntime.class),
            mock(io.agentmanager.framework.service.WorkspaceReader.class),
            new org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec>() {
                @Override
                public io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec getIfAvailable()
                        throws org.springframework.beans.BeansException {
                    return null;
                }

                @Override
                public io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec getObject() {
                    return null;
                }

                @Override
                public io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec getObject(Object... args) {
                    return null;
                }

                @Override
                public void ifAvailable(java.util.function.Consumer<io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec> consumer) {
                }

                @Override
                public java.util.stream.Stream<io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec> stream() {
                    return java.util.stream.Stream.empty();
                }

                @Override
                public io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec getIfUnique() {
                    return null;
                }
            });
        // OafPackageTools 已迁移至 backend MCP（check_oaf_package/create_oaf_zip），
        // 自定义工具只剩框架通用能力（BusinessTools + FileTools）
        assertEquals(java.util.Arrays.asList(tool, fileTools),
            config.customTools(tool, fileTools));
    }

    @Test
    void dataSourceShouldUseCheckpointProperties() {
        var props = new AgentManagerProperties(
            emptyLlm(), emptyServer(),
            new AgentManagerProperties.CheckpointConfig(
                "jdbc:mysql://localhost:3306/test", "u", "p", "test"),
            "/config", "", cleanupConfig(), emptyFileConfig(),new AgentManagerProperties.SseConfig(20, 5, 256, 300), harnessConfig());

        var ds = config.dataSource(props);
        assertInstanceOf(HikariDataSource.class, ds);
        assertEquals("jdbc:mysql://localhost:3306/test", ((HikariDataSource) ds).getJdbcUrl());
    }

    @Test
    void dataSourceShouldApplyHarnessPoolSettings() {
        var props = new AgentManagerProperties(
            emptyLlm(), emptyServer(),
            new AgentManagerProperties.CheckpointConfig(
                "jdbc:mysql://localhost:3306/test", "u", "p", "test"),
            "/config", "", cleanupConfig(), emptyFileConfig(),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            new AgentManagerProperties.HarnessConfig(
                20, 30, 180, 30, true, 10, 8000, 60,
                30, 10, true, true, 7, 1, 5000L, 60000L, 900000L));

        var ds = config.dataSource(props);
        assertEquals(7, ((HikariDataSource) ds).getMaximumPoolSize());
        assertEquals(1, ((HikariDataSource) ds).getMinimumIdle());
        assertEquals(5000L, ((HikariDataSource) ds).getConnectionTimeout());
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

        var factory = new io.agentmanager.framework.service.HarnessAgentFactory(
            props, ws, mcp, List.of((io.agentmanager.framework.tool.CustomTool) new BusinessTools()));
        assertThrows(RuntimeException.class,
            () -> factory.build(oaf, store, new LLMLogger(), null, null,
                mock(io.agentmanager.framework.service.ModelCatalog.class), null));
    }

    // ---------- buildPermissionContext：自定义工具 HITL 装配（hitl-permission-plan 6.1） ----------

    private OafConfig oafForPermission(boolean requireConfirmation, java.util.Map<String, String> permissionTools) {
        var oaf = mock(OafConfig.class);
        when(oaf.runtimeConfig()).thenReturn(new OafConfig.RuntimeConfig(
            0.7, 4096, requireConfirmation, "default", permissionTools));
        return oaf;
    }

    private McpToolRegistrar.PermissionRuleResult permCfg(java.util.Map<String, String> mcpTools,
                                                          java.util.Set<String> mcpNames) {
        return new McpToolRegistrar.PermissionRuleResult(
            io.agentscope.core.permission.PermissionMode.DEFAULT, mcpTools, mcpNames);
    }

    @Test
    void permissionContextShouldBeNullWhenNothingDeclared() {
        assertNull(factory.buildPermissionContext(
            oafForPermission(false, java.util.Map.of()), permCfg(java.util.Map.of(), java.util.Set.of()),
            java.util.Set.of("present_url")));
    }

    @Test
    void customToolAskDeclarationShouldEnablePermissionSystem() {
        var ctx = factory.buildPermissionContext(
            oafForPermission(false, java.util.Map.of("present_url", "ask")),
            permCfg(java.util.Map.of(), java.util.Set.of()),
            java.util.Set.of("present_url", "echo"));

        assertNotNull(ctx, "custom tool declaration alone should enable permission system");
        assertTrue(ctx.getAskRules().containsKey("present_url"));
        assertFalse(ctx.getAllowRules().containsKey("present_url"), "ask must replace auto-allow");
        // 未声明的自定义工具与内置工具保持自动放行
        assertTrue(ctx.getAllowRules().containsKey("echo"));
        assertTrue(ctx.getAllowRules().containsKey("write_file"));
    }

    @Test
    void customToolDenyDeclarationShouldProduceDenyRule() {
        var ctx = factory.buildPermissionContext(
            oafForPermission(false, java.util.Map.of("echo", "deny")),
            permCfg(java.util.Map.of(), java.util.Set.of()),
            java.util.Set.of("echo"));

        assertTrue(ctx.getDenyRules().containsKey("echo"));
        assertFalse(ctx.getAllowRules().containsKey("echo"));
    }

    @Test
    void mcpNameCollisionShouldWinOverCustomDeclaration() {
        var ctx = factory.buildPermissionContext(
            oafForPermission(false, java.util.Map.of("write_file", "ask")),
            permCfg(java.util.Map.of(), java.util.Set.of("write_file")),
            java.util.Set.of());

        // 与 MCP 裸名冲突时以 MCP 规则为准：MCP 未显式声明 → 兜底 allow（require_confirmation=false）
        assertFalse(ctx.getAskRules().containsKey("write_file"));
        assertTrue(ctx.getAllowRules().containsKey("write_file"));
    }

    @Test
    void requireConfirmationShouldStayMcpOnly() {
        var ctx = factory.buildPermissionContext(
            oafForPermission(true, java.util.Map.of()),
            permCfg(java.util.Map.of(), java.util.Set.of("mcp_publish")),
            java.util.Set.of("present_url"));

        // require_confirmation=true 仅兜底 MCP 工具；自定义工具未声明仍自动放行
        assertTrue(ctx.getAskRules().containsKey("mcp_publish"));
        assertTrue(ctx.getAllowRules().containsKey("present_url"));
        assertFalse(ctx.getAskRules().containsKey("present_url"));
    }

    /**
     * 真实 SDK 评估链路验证：自定义 @Tool 注册为 ReflectiveFunctionTool(ToolBase) 后，
     * PermissionEngine 按注册名命中 ask/deny/allow 规则（hitl-permission-plan 15.1）。
     */
    @Test
    void permissionEngineShouldEvaluateCustomToolRules() {
        var ctx = factory.buildPermissionContext(
            oafForPermission(false, java.util.Map.of("get_current_time", "ask", "echo", "deny")),
            permCfg(java.util.Map.of(), java.util.Set.of()),
            java.util.Set.of("get_current_time", "echo"));

        var toolkit = new io.agentscope.core.tool.Toolkit();
        toolkit.registerTool(new BusinessTools());
        var engine = new io.agentscope.core.permission.PermissionEngine(ctx);

        var askTool = (io.agentscope.core.tool.ToolBase) toolkit.getTool("get_current_time");
        assertEquals(io.agentscope.core.permission.PermissionBehavior.ASK,
            engine.checkPermission(askTool, java.util.Map.of()).block().getBehavior());

        var denyTool = (io.agentscope.core.tool.ToolBase) toolkit.getTool("echo");
        assertEquals(io.agentscope.core.permission.PermissionBehavior.DENY,
            engine.checkPermission(denyTool, java.util.Map.of()).block().getBehavior());
    }

    private static AgentManagerProperties propsForLlm() {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig(
                "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0),
            emptyServer(),
            new AgentManagerProperties.CheckpointConfig(
                "jdbc:mysql://localhost:3306/cp", "u", "p", "cp"),
            "/config", "", cleanupConfig(), emptyFileConfig(),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300), harnessConfig());
    }

    private static AgentManagerProperties.HarnessConfig harnessConfig() {
        return AgentManagerProperties.HarnessConfig.defaults();
    }

    private static AgentManagerProperties.CleanupConfig cleanupConfig() {
        return new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7);
    }

    private static AgentManagerProperties.FileConfig emptyFileConfig() {
        return new AgentManagerProperties.FileConfig(true, 20, 20,
            "image/*,text/plain,text/markdown,text/csv,application/pdf",
            5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", "");
    }

    private static AgentManagerProperties.LLMConfig emptyLlm() {
        return new AgentManagerProperties.LLMConfig("", "", "", "openai", 0.7, 4096, 120, true, 0);
    }

    @Test
    void chatModelShouldApplyContextLengthWhenConfigured() {
        var llm = new AgentManagerProperties.LLMConfig(
            "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 131072);

        var model = factory.buildChatModel(llm, harnessConfig());

        assertEquals(131072, model.getContextWindowSize());
    }

    @Test
    void chatModelShouldIgnoreContextLengthWhenNotConfigured() {
        var llm = new AgentManagerProperties.LLMConfig(
            "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0);

        var model = factory.buildChatModel(llm, harnessConfig());

        assertEquals(0, model.getContextWindowSize());
    }

    // ---------- AGENT_MEMORY_ENABLED：记忆总开关（完全关闭 = hooks + tools + 沙箱门控） ----------

    @Test
    void defaultsShouldEnableMemory() {
        assertTrue(AgentManagerProperties.HarnessConfig.defaults().memoryEnabled(),
            "AGENT_MEMORY_ENABLED 缺省必须为 true（行为与历史版本一致）");
    }

    /**
     * 全量构建 harnessAgent 并返回 toolkit 注册的工具名集合。
     * 依赖项与现有用例同口径打桩：真实 InMemoryStore 兜底 DistributedStore（框架构建期会触碰），
     * 权限规则为空集（buildPermissionContext 返回 null，零侵入路径）。
     */
    private java.util.Set<String> buildAgentToolNames(AgentManagerProperties.HarnessConfig harness) throws Exception {
        var oaf = mock(OafConfig.class);
        when(oaf.name()).thenReturn("test-agent");
        when(oaf.systemPrompt()).thenReturn("prompt");
        when(oaf.runtimeConfig()).thenReturn(new OafConfig.RuntimeConfig(
            0.7, 4096, false, "default", java.util.Map.of()));
        var ws = mock(WorkspaceInitializer.class);
        when(ws.initialize(any(java.nio.file.Path.class), any(OafConfig.class)))
            .thenReturn(java.nio.file.Files.createTempDirectory("memory-switch-test"));
        var mcp = mock(McpToolRegistrar.class);
        when(mcp.collectPermissionRules(oaf)).thenReturn(permCfg(java.util.Map.of(), java.util.Set.of()));

        var store = org.mockito.Mockito.mock(DistributedStore.class);
        when(store.baseStore()).thenReturn(new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
        // SDK build() 校验 RemoteFilesystemSpec 必须搭配分布式 state store（拒绝 JsonFile/InMemory
        // 两种本地实现），mock 一即可通过校验（isLocalSession 仅 instanceof 这两个类）
        when(store.agentStateStore())
            .thenReturn(org.mockito.Mockito.mock(io.agentscope.core.state.AgentStateStore.class));

        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig(
                "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0),
            emptyServer(), emptyCheckpoint(), "/config", "", cleanupConfig(), emptyFileConfig(),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300), harness);

        var factory = new io.agentmanager.framework.service.HarnessAgentFactory(
            props, ws, mcp, List.of((io.agentmanager.framework.tool.CustomTool) new BusinessTools()));
        var agent = factory.build(oaf, store, new LLMLogger(), null, null,
            mock(io.agentmanager.framework.service.ModelCatalog.class), null);
        return new java.util.TreeSet<>(agent.getToolkit().getToolNames());
    }

    @Test
    void agentWithMemoryDisabledShouldExcludeMemoryTools() throws Exception {
        // memoryEnabled=false：完全关闭 = 不注册 memory_search / memory_get / memory_save
        var harness = new AgentManagerProperties.HarnessConfig(
            20, 30, 180, 30, false, 10, 8000, 60,
            30, 10, true, true, 10, 2, 30000L, 600000L, 1800000L);
        var names = buildAgentToolNames(harness);

        assertFalse(names.contains("memory_search"), "memory 关闭时不应注册 memory_search");
        assertFalse(names.contains("memory_get"), "memory 关闭时不应注册 memory_get");
        assertFalse(names.contains("memory_save"), "memory 关闭时不应注册 memory_save");
    }

    @Test
    void agentWithDefaultMemoryShouldIncludeMemoryTools() throws Exception {
        // 默认（memoryEnabled=true）：三个 memory_* 工具照常注册
        var names = buildAgentToolNames(harnessConfig());

        assertTrue(names.contains("memory_search"), "memory 默认开启时应注册 memory_search");
        assertTrue(names.contains("memory_get"), "memory 默认开启时应注册 memory_get");
        assertTrue(names.contains("memory_save"), "memory 默认开启时应注册 memory_save");
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