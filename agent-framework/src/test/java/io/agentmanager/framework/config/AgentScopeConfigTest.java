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
            propsForLlm(), null, null, java.util.List.of(),
            mock(io.agentmanager.framework.service.RemoteConfirmBridge.class), null, null,
            mock(io.agentmanager.framework.service.SessionToolScopeService.class));

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
            mock(io.agentmanager.framework.service.SessionUserStore.class));
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
                30, 80000, 20000, 10, true, true, 7, 1, 5000L, 60000L, 900000L));

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
            props, ws, mcp, List.of((io.agentmanager.framework.tool.CustomTool) new BusinessTools()),
            mock(io.agentmanager.framework.service.RemoteConfirmBridge.class), null, null,
            mock(io.agentmanager.framework.service.SessionToolScopeService.class));
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
     * frontmatter 声明 MCP 注册名（含合成 server 的 mcp__openviking__*，issue #415）：
     * 契约不变——规则来自 mcp-configs/{server}/config.yaml permissions.tools，
     * frontmatter 声明不生效并打告警（修复前静默 continue）。
     */
    @Test
    void frontmatterDeclarationForMcpRegisteredNameShouldBeIgnored() {
        var ctx = factory.buildPermissionContext(
            oafForPermission(false, java.util.Map.of("mcp__openviking__write", "deny")),
            permCfg(java.util.Map.of("mcp__openviking__write", "ask"),
                java.util.Set.of("mcp__openviking__write")),
            java.util.Set.of());

        // 规则来自 mcp-configs permissions.tools（ask），frontmatter 的 deny 不生效
        assertFalse(ctx.getDenyRules().containsKey("mcp__openviking__write"));
        assertTrue(ctx.getAskRules().containsKey("mcp__openviking__write"));
    }

    /**
     * 重排后链路（issue #415）：collectPermissionRules 输出的合成 server 注册名走
     * buildPermissionContext step② 全套规则装配（含 mcp__openviking__ 前缀键）。
     */
    @Test
    void syntheticMcpNamesShouldGetFullPermissionRules() {
        var ctx = factory.buildPermissionContext(
            oafForPermission(true, java.util.Map.of()),
            permCfg(java.util.Map.of("mcp__openviking__write", "ask"),
                java.util.Set.of("mcp__openviking__write", "mcp__openviking__read")),
            java.util.Set.of());

        // 显式声明 ask 生效；未声明的合成工具兜底 require_confirmation → ask（规则面完整覆盖）
        assertTrue(ctx.getAskRules().containsKey("mcp__openviking__write"));
        assertTrue(ctx.getAskRules().containsKey("mcp__openviking__read"));
    }

    // ---------- 合成 MCP 注入守卫（issue #415 评审 P3，全构建验证） ----------

    /**
     * OVK_MCP_SHARED_CONTEXT 全构建：frontmatter 已声明 openviking 时合成注入跳过
     * （declared 声明优先），未声明时经 registerSyntheticClient 注入。viking 依赖
     * @Autowired 字段注入，测试按既有先例（ThreadControllerStopTest）用
     * ReflectionTestUtils 置桩。
     */
    private io.agentmanager.framework.service.HarnessAgentFactory vikingEnabledFactory(McpToolRegistrar registrar)
            throws Exception {
        var ws = mock(WorkspaceInitializer.class);
        when(ws.initialize(any(java.nio.file.Path.class), any(OafConfig.class)))
            .thenReturn(java.nio.file.Files.createTempDirectory("viking-guard-test"));
        var factory = new io.agentmanager.framework.service.HarnessAgentFactory(
            propsForLlm(), ws, registrar, java.util.List.of(),
            mock(io.agentmanager.framework.service.RemoteConfirmBridge.class), null, null,
            mock(io.agentmanager.framework.service.SessionToolScopeService.class));
        var vikingConfig = new io.agentmanager.framework.config.VikingConfig(
            "openviking", "http://localhost:1933", "acct", "admin-key", "seed",
            false, true, 2000, 300);
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "vikingConfig", vikingConfig);
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "vikingMemoryClient",
            mock(io.agentmanager.framework.service.VikingMemoryClient.class));
        return factory;
    }

    private OafConfig oafForBuild(java.util.List<OafConfig.McpServerConfig> mcpServers) {
        var oaf = mock(OafConfig.class);
        when(oaf.name()).thenReturn("test-agent");
        when(oaf.systemPrompt()).thenReturn("prompt");
        when(oaf.runtimeConfig()).thenReturn(new OafConfig.RuntimeConfig(
            0.7, 4096, false, "default", java.util.Map.of()));
        when(oaf.skills()).thenReturn(java.util.List.of());
        when(oaf.subAgents()).thenReturn(java.util.List.of());
        when(oaf.mcpServers()).thenReturn(mcpServers);
        return oaf;
    }

    private io.agentscope.harness.agent.DistributedStore buildableStore() {
        var store = mock(DistributedStore.class);
        when(store.baseStore()).thenReturn(new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
        when(store.agentStateStore())
            .thenReturn(mock(io.agentscope.core.state.AgentStateStore.class));
        return store;
    }

    @Test
    void syntheticOpenVikingInjectionShouldSkipWhenFrontmatterDeclaresSameName() throws Exception {
        var registrar = mock(McpToolRegistrar.class);
        when(registrar.collectPermissionRules(any(OafConfig.class)))
            .thenReturn(permCfg(java.util.Map.of(), java.util.Set.of()));
        var factory = vikingEnabledFactory(registrar);

        var oaf = oafForBuild(java.util.List.of(
            new OafConfig.McpServerConfig("openviking", "openviking", "1.0.0", "", false)));
        factory.build(oaf, buildableStore(), new LLMLogger(), null, null,
            mock(io.agentmanager.framework.service.ModelCatalog.class), null);

        org.mockito.Mockito.verify(registrar, org.mockito.Mockito.never())
            .registerSyntheticClient(any(), any(), any());
    }

    @Test
    void syntheticOpenVikingInjectionShouldRegisterWhenNotDeclared() throws Exception {
        var registrar = mock(McpToolRegistrar.class);
        when(registrar.collectPermissionRules(any(OafConfig.class)))
            .thenReturn(permCfg(java.util.Map.of(), java.util.Set.of()));
        var factory = vikingEnabledFactory(registrar);

        var oaf = oafForBuild(java.util.List.of());
        factory.build(oaf, buildableStore(), new LLMLogger(), null, null,
            mock(io.agentmanager.framework.service.ModelCatalog.class), null);

        org.mockito.Mockito.verify(registrar).registerSyntheticClient(
            any(), any(io.agentmanager.framework.model.OafConfig.McpServerConfig.class), any());
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
                "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0, "", null),
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
        return new AgentManagerProperties.LLMConfig("", "", "", "openai", 0.7, 4096, 120, true, 0, "", null);
    }

    @Test
    void chatModelShouldApplyContextLengthWhenConfigured() {
        var llm = new AgentManagerProperties.LLMConfig(
            "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 131072, "", null);

        var model = factory.buildChatModel(llm, harnessConfig());

        assertEquals(131072, model.getContextWindowSize());
    }

    @Test
    void chatModelShouldIgnoreContextLengthWhenNotConfigured() {
        var llm = new AgentManagerProperties.LLMConfig(
            "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0, "", null);

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
                "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0, "", null),
            emptyServer(), emptyCheckpoint(), "/config", "", cleanupConfig(), emptyFileConfig(),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300), harness);

        var factory = new io.agentmanager.framework.service.HarnessAgentFactory(
            props, ws, mcp, List.of((io.agentmanager.framework.tool.CustomTool) new BusinessTools()),
            mock(io.agentmanager.framework.service.RemoteConfirmBridge.class), null, null,
            mock(io.agentmanager.framework.service.SessionToolScopeService.class));
        var agent = factory.build(oaf, store, new LLMLogger(), null, null,
            mock(io.agentmanager.framework.service.ModelCatalog.class), null);
        return new java.util.TreeSet<>(agent.getToolkit().getToolNames());
    }

    @Test
    void agentWithMemoryDisabledShouldExcludeMemoryTools() throws Exception {
        // memoryEnabled=false：完全关闭 = 不注册 memory_search / memory_get / memory_save
        var harness = new AgentManagerProperties.HarnessConfig(
            20, 30, 180, 30, false, 10, 8000, 60,
            30, 80000, 20000, 10, true, true, 10, 2, 30000L, 600000L, 1800000L);
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

    /**
     * VikingSessionCommit 装配门控（issue #418 B-05c）：client/sessionCommit/memoryEnabled
     * 三条件齐备才装配，AGENT_MEMORY_ENABLED=false 不得再绕过记忆总开关。
     */
    @Test
    void vikingSessionCommitGateRequiresMemoryEnabled() {
        var cfgOn = new io.agentmanager.framework.config.VikingConfig(
            "openviking", "http://localhost:1933", "acct", "admin-key", "seed",
            true, false, 2000, 300);
        var cfgCommitOff = new io.agentmanager.framework.config.VikingConfig(
            "openviking", "http://localhost:1933", "acct", "admin-key", "seed",
            false, false, 2000, 300);
        var client = mock(io.agentmanager.framework.service.VikingMemoryClient.class);
        var harnessOn = AgentManagerProperties.HarnessConfig.defaults();
        var harnessOff = new AgentManagerProperties.HarnessConfig(
            20, 30, 180, 30, false, 10, 8000, 60,
            30, 80000, 20000, 10, true, true, 10, 2, 30000L, 600000L, 1800000L);

        assertFalse(AgentScopeConfig.vikingSessionCommitEnabled(null, cfgOn, harnessOn),
            "无 VikingMemoryClient 不得装配");
        assertFalse(AgentScopeConfig.vikingSessionCommitEnabled(client, cfgCommitOff, harnessOn),
            "sessionCommit=false 不得装配");
        assertFalse(AgentScopeConfig.vikingSessionCommitEnabled(client, cfgOn, harnessOff),
            "AGENT_MEMORY_ENABLED=false 必须门控（issue #418）");
        assertTrue(AgentScopeConfig.vikingSessionCommitEnabled(client, cfgOn, harnessOn),
            "三条件齐备才装配");
    }

    /**
     * WorkspaceReader 本地记忆门控（issue #419 B-07b）：provider=openviking（VikingMemoryClient
     * bean 在位）时必须关闭本地文件记忆注入/回写，消除双记忆源；provider=local 保持现状；
     * AGENT_MEMORY_ENABLED=false 时无论 provider 均关闭（既有语义不变）。
     */
    @Test
    void workspaceReaderShouldDisableLocalMemoryUnderVikingProvider() {
        var oaf = mock(OafConfig.class);
        when(oaf.name()).thenReturn("test-agent");
        var store = mock(DistributedStore.class);
        when(store.baseStore()).thenReturn(new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());

        var harnessOff = new AgentManagerProperties.HarnessConfig(
            20, 30, 180, 30, false, 10, 8000, 60,
            30, 80000, 20000, 10, true, true, 10, 2, 30000L, 600000L, 1800000L);
        var propsOff = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig(
                "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0, "", null),
            emptyServer(), emptyCheckpoint(), "/config", "", cleanupConfig(), emptyFileConfig(),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300), harnessOff);

        var withClient = providerOf(mock(io.agentmanager.framework.service.VikingMemoryClient.class));
        var noClient = providerOf(null);

        assertFalse(config.workspaceReader(store, oaf, propsForLlm(), withClient).isMemoryEnabled(),
            "provider=openviking 时本地文件记忆必须关闭（issue #419 双记忆源）");
        assertTrue(config.workspaceReader(store, oaf, propsForLlm(), noClient).isMemoryEnabled(),
            "provider=local 时本地文件记忆保持现状");
        assertFalse(config.workspaceReader(store, oaf, propsOff, withClient).isMemoryEnabled(),
            "AGENT_MEMORY_ENABLED=false 时无论 provider 均关闭");
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.service.VikingMemoryClient> providerOf(
            io.agentmanager.framework.service.VikingMemoryClient client) {
        var provider = (org.springframework.beans.factory.ObjectProvider<io.agentmanager.framework.service.VikingMemoryClient>)
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    // ===== SDK 遮蔽自检（sdk-stager-shadow-design C-3，issue #426）=====

    @Test
    void shadowSelfCheckMustPassForRuntimeLoadedShadowClass() {
        // mvn test 下遮蔽类由 target/classes 加载（classpath 先于依赖 jar），自检必须通过
        assertDoesNotThrow(() -> AgentScopeConfig.verifyShadowActive(
            io.agentscope.harness.agent.skill.runtime.MarketplaceStager.class));
    }

    @Test
    void shadowSelfCheckMustRejectClassWithoutMarkerField() {
        // 负分支：原版 SDK 类没有 SHADOW_MARKER 字段——自检必须抛出而非静默放行
        // （原实现引用编译期常量被 javac 内联，任何加载形态下都恒真放行）
        var e = assertThrows(IllegalStateException.class,
            () -> AgentScopeConfig.verifyShadowActive(String.class));
        assertTrue(e.getMessage().contains("shadow INACTIVE"), e.getMessage());
    }

    @Test
    void shadowSelfCheckMustFailFastOnOriginalSdkClassFromJar() throws Exception {
        // 真实复现（issue #426）：classpath 顺序回归时运行期加载到 SDK jar 原版类——
        // 旧实现（消费方 ldc 内联常量）对该场景静默放行；对原版类（无 SHADOW_MARKER 字段）
        // 自检必须硬失败。child-first loader 仅对遮蔽 FQN 强制从 jar 加载，依赖类走标准委派
        var jarUrl = io.agentscope.harness.agent.skill.WorkspaceSkillRepository.class
            .getProtectionDomain().getCodeSource().getLocation();
        var loader = new java.net.URLClassLoader(new java.net.URL[]{jarUrl},
            AgentScopeConfigTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals("io.agentscope.harness.agent.skill.runtime.MarketplaceStager")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (this.getClassLoadingLock(name)) {
                    var c = findLoadedClass(name);
                    if (c == null) {
                        c = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
        };
        var original = loader.loadClass("io.agentscope.harness.agent.skill.runtime.MarketplaceStager");
        assertThrows(NoSuchFieldException.class, () -> original.getField("SHADOW_MARKER"),
            "SDK jar 原版类不得有 SHADOW_MARKER 字段（自检判据前提）");
        var e = assertThrows(IllegalStateException.class,
            () -> AgentScopeConfig.verifyShadowActive(original),
            "自检对 SDK jar 原版类必须 fail-fast（issue #426 复现场景）");
        assertTrue(e.getMessage().contains("shadow INACTIVE"), e.getMessage());
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