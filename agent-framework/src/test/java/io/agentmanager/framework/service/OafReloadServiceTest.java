package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.config.OafConfigLoader;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * OafReloadService 单测：指纹分流（A/B 路径）、reload 结果结构、防抖、
 * 失败回滚语义（重建失败保持旧 agent）。
 * HarnessAgentFactory / McpToolRegistrar 等重依赖以 mock 桩替，聚焦编排逻辑。
 */
class OafReloadServiceTest {

    @TempDir
    Path tempDir;

    private OafConfigHolder holder;
    private OafConfigLoader loader;
    private AgentManagerProperties props;
    private HarnessAgentFactory factory;
    private McpToolRegistrar registrar;
    private McpManager mcpManager;
    private McpResourceProxy resourceProxy;
    private WorkspaceInitializer workspaceInitializer;
    private AgentRuntimeService runtimeService;
    private A2aAgentRefHolder a2aHolder;
    private org.springframework.beans.factory.ObjectProvider<OpenSandboxFilesystemSpec> noopSandboxProvider;

    private OafReloadService service;

    @BeforeEach
    void setUp() throws Exception {
        writeAgentsMd("name: test-agent\nslug: acme/test-agent\n---\nbody");

        loader = mock(OafConfigLoader.class);
        props = mock(AgentManagerProperties.class);
        factory = mock(HarnessAgentFactory.class);
        registrar = mock(McpToolRegistrar.class);
        mcpManager = mock(McpManager.class);
        resourceProxy = mock(McpResourceProxy.class);
        workspaceInitializer = mock(WorkspaceInitializer.class);
        runtimeService = mock(AgentRuntimeService.class);
        a2aHolder = new A2aAgentRefHolder(new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public HarnessAgent getIfAvailable() {
                return null;
            }

            @Override
            public HarnessAgent getObject() {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public HarnessAgent getObject(Object... args) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public HarnessAgent getIfUnique() {
                return null;
            }
        });
        noopSandboxProvider = new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public OpenSandboxFilesystemSpec getIfAvailable() {
                return null;
            }

            @Override
            public OpenSandboxFilesystemSpec getObject() {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public OpenSandboxFilesystemSpec getObject(Object... args) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public OpenSandboxFilesystemSpec getIfUnique() {
                return null;
            }
        };

        var oldAgent = mock(HarnessAgent.class);
        var oldToolkit = mock(io.agentscope.core.tool.Toolkit.class);
        when(oldAgent.getToolkit()).thenReturn(oldToolkit);
        when(runtimeService.swapAgent(any())).thenReturn(oldAgent);
        when(runtimeService.getAgent()).thenReturn(oldAgent);
        a2aHolder.update(oldAgent);

        when(props.configDir()).thenReturn(tempDir.toString());
        when(props.resolvedWorkspaceBaseDir()).thenReturn(tempDir.resolve("ws-base").toString());

        holder = new OafConfigHolder(oafWithMcpServers("server-a"));
        when(loader.load()).thenReturn(oafWithMcpServers("server-a"));

        // injectBuildDependencies 是 package-private，同包测试可达：直接置桩走反射不必要，
        // 工厂 mock 后 build() 返回桩 agent，依赖注入字段未置也不走真实路径。
        var newAgent = mock(HarnessAgent.class);
        when(factory.build(any(), any(), any(), any(), any(), any(), any())).thenReturn(newAgent);
        when(registrar.getRegisteredServerNames()).thenReturn(java.util.Set.of("server-a"));
        var wrapper = mock(io.agentscope.core.tool.mcp.McpClientWrapper.class);
        when(registrar.getRegisteredWrapper("server-a")).thenReturn(wrapper);
        when(wrapper.listTools()).thenReturn(reactor.core.publisher.Mono.just(List.of()));

        service = new OafReloadService(loader, holder, props, factory, registrar,
            resourceProxy, workspaceInitializer, runtimeService, a2aHolder,
            mock(DistributedStore.class), new LLMLogger(), mock(UiContextStore.class),
            mock(SessionUserStore.class),
            mock(io.agentmanager.framework.service.ModelCatalog.class), noopSandboxProvider);
    }

    private OafConfig oafWithMcpServers(String serverName) {
        return new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of(), "You are a test agent.",
            List.of(),
            List.of(new OafConfig.McpServerConfig("v", serverName, "1.0.0", serverName, false)),
            List.of(), List.of(), List.of(),
            null,
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            null, java.util.Map.of());
    }

    private void writeAgentsMd(String content) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("AGENTS.md"), content);
    }

    @Test
    void reloadShouldNoopWhenFingerprintUnchanged() throws IOException {
        // 第一次 reload 将构造时快照作为基线；若无变化 → noop
        var r1 = service.reload();
        // 第二次（无任何文件变化）必然 noop
        var r2 = service.reload();
        assertEquals("noop", r2.scope());
        assertFalse(r2.fingerprintChanged());
        // r1 是否 noop 取决于构造与首次扫描间 mtime 是否变化，不作断言
    }

    @Test
    void reloadShouldRouteToMcpPathWhenOnlyMcpConfigsChange() throws IOException {
        // 先建立基线指纹
        service.reload();

        // 只动 mcp-configs（AGENTS.md 不变）
        var mcpDir = tempDir.resolve("mcp-configs/server-a");
        Files.createDirectories(mcpDir);
        Files.writeString(mcpDir.resolve("config.yaml"), "connection:\n  type: sse\n  url: http://x\n");

        var result = service.reload();
        assertEquals("mcp", result.scope());
        assertTrue(result.fingerprintChanged());
        assertFalse(result.agentRebuilt());
        assertEquals(1, result.mcpServers().size());
        assertEquals("server-a", result.mcpServers().get(0).get("server"));
    }

    /**
     * A 路径「声明已删除」循环必须豁免合成 server（issue #415）：openviking 不由
     * frontmatter 声明，修复前每次 mcp-configs 变更都被误判已删除（摘工具+驱逐句柄
     * 且永不重注册=永久下线）；豁免后工具保留、句柄不驱逐。
     */
    @Test
    void reloadMcpAllShouldNotRemoveSyntheticServers() throws IOException {
        when(registrar.getRegisteredServerNames()).thenReturn(java.util.Set.of("server-a", "openviking"));
        when(registrar.syntheticServers()).thenReturn(java.util.Map.of("openviking",
            new McpToolRegistrar.SyntheticServer(
                HarnessAgentFactory.syntheticOpenvikingServerConfig(),
                () -> null)));

        var mcpDir = tempDir.resolve("mcp-configs/server-a");
        Files.createDirectories(mcpDir);
        Files.writeString(mcpDir.resolve("config.yaml"), "connection:\n  type: sse\n  url: http://x\n");
        var result = service.reload();

        assertEquals("mcp", result.scope());
        assertTrue(result.mcpServers().stream().noneMatch(e -> "openviking".equals(e.get("server"))),
            "合成 server 不进 removed/reloaded 明细");
        org.mockito.Mockito.verify(registrar, org.mockito.Mockito.never())
            .clearServer(any(), org.mockito.ArgumentMatchers.eq("openviking"));
        org.mockito.Mockito.verify(registrar, org.mockito.Mockito.never())
            .evictSharedWrapper("openviking");
    }

    @Test
    void reloadShouldRouteToAgentPathWhenAgentsMdChanges() throws IOException {
        service.reload();

        // touch AGENTS.md（mtime 变化）
        Mtc.tick(5);
        writeAgentsMd("name: test-agent\nslug: acme/test-agent\n---\nnew body");

        var result = service.reload();
        assertEquals("agent", result.scope());
        assertTrue(result.agentRebuilt());
        // 原子切换验证：runtimeService.swapAgent 被调（新 agent 生效）、holder 更新、a2a holder 更新
        org.mockito.Mockito.verify(runtimeService).swapAgent(any());
        assertEquals("server-a", holder.get().mcpServers().get(0).server());
        assertNotNull(a2aHolder.get());
    }

    @Test
    void reloadAgentShouldKeepOldAgentWhenRebuildFails() throws IOException {
        service.reload();
        var oldAgent = runtimeService.getAgent();

        when(loader.load()).thenThrow(new RuntimeException("bad frontmatter"));

        assertThrows(RuntimeException.class, () -> service.reloadAgent());
        // 旧 agent 引用不变
        assertSame(oldAgent, runtimeService.getAgent());
    }

    @Test
    void reloadAgentShouldKeepOldAgentWhenFactoryFails() throws IOException {
        service.reload();
        var oldAgent = runtimeService.getAgent();

        Mtc.tick(5);
        writeAgentsMd("name: test-agent\n---\nchanged");
        when(factory.build(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("build failed"));

        assertThrows(java.io.IOException.class, () -> service.reloadAgent());
        assertSame(oldAgent, runtimeService.getAgent());
    }

    @Test
    void reloadDebouncedShouldRejectConcurrentCall() throws IOException {
        // reloading 标记并发防抖：占住后第二次调用返回 noop
        assertTrue(service.debounceAcquireForTest());
        var result = service.reloadDebounced();
        assertEquals("noop", result.scope());
        assertEquals("reload in progress", result.error());
        service.debounceReleaseForTest();

        // 释放后重入不再被拒（错误信息不再是 in-progress）；
        // 指纹未变 → noop，mtime 抖动 → 走正常 reload，两者均合法
        var ok = service.reloadDebounced();
        assertNotEquals("reload in progress", ok.error());
    }

    @Test
    void reloadMcpServerShouldReportNotDeclaredForUnknownServer() {
        var result = service.reloadMcpServer("no-such-server");
        assertEquals("mcp", result.scope());
        assertEquals("not-declared", result.mcpServers().get(0).get("action"));
        assertNotNull(result.error());
    }

    // ---------- A 路径 MCP reload 与 SessionAgentCache 的世代一致（issue #491） ----------

    /**
     * issue #491 复现：A 路径 reload 刷新的是单例 toolkit 与全局 registeredTools
     * （SSE directReturn 标记面），若不驱逐 SessionAgentCache，存量会话继续持旧
     * Toolkit 按旧配置执行（标记/执行脱节最长一个 TTL）。修复后必须换代重建。
     */
    @Test
    void reloadMcpPathShouldRebuildCachedSessionAgentsForExistingSessions() throws IOException {
        when(registrar.registerOne(any(), any())).thenReturn("server-a");
        var realRuntime = new AgentRuntimeService(oafWithMcpServers("server-a"),
            runtimeService.getAgent(), List.of(), new LLMLogger(),
            mock(ConfirmContextStore.class), true, 10, 30);
        realRuntime.setSessionAgentFactory(() -> mock(HarnessAgent.class));
        var svc = new OafReloadService(loader, holder, props, factory, registrar,
            resourceProxy, workspaceInitializer, realRuntime, a2aHolder,
            mock(DistributedStore.class), new LLMLogger(), mock(UiContextStore.class),
            mock(SessionUserStore.class),
            mock(io.agentmanager.framework.service.ModelCatalog.class), noopSandboxProvider);

        var cached = realRuntime.getAgent("sid-1");
        var mcpDir = tempDir.resolve("mcp-configs/server-a");
        Files.createDirectories(mcpDir);
        Files.writeString(mcpDir.resolve("config.yaml"), "connection:\n  type: sse\n  url: http://x\n");

        var result = svc.reload();

        assertEquals("mcp", result.scope());
        assertNotSame(cached, realRuntime.getAgent("sid-1"),
            "A 路径 reload 后存量会话必须换代重建，不得沿用旧配置实例");
    }

    @Test
    void reloadMcpAllShouldEvictAllSessionAgents() throws IOException {
        when(registrar.registerOne(any(), any())).thenReturn("server-a");
        var mcpDir = tempDir.resolve("mcp-configs/server-a");
        Files.createDirectories(mcpDir);
        Files.writeString(mcpDir.resolve("config.yaml"), "connection:\n  type: sse\n  url: http://x\n");

        var result = service.reload();

        assertEquals("mcp", result.scope());
        org.mockito.Mockito.verify(runtimeService).evictAllSessionAgents();
    }

    @Test
    void reloadMcpServerSuccessShouldEvictAllSessionAgents() {
        when(registrar.registerOne(any(), any())).thenReturn("server-a");

        var result = service.reloadMcpServer("server-a");

        assertEquals("reloaded", result.mcpServers().get(0).get("action"));
        org.mockito.Mockito.verify(runtimeService).evictAllSessionAgents();
    }

    /** 状态未变（未声明/换芯失败保留旧注册）不驱逐：避免无谓的全量会话重建 */
    @Test
    void reloadMcpServerWithoutStateChangeShouldKeepSessionAgents() {
        service.reloadMcpServer("no-such-server");
        org.mockito.Mockito.verify(runtimeService, org.mockito.Mockito.never())
            .evictAllSessionAgents();
    }

    // ---------- 合成注册表代际收口（issue #415 评审 g1） ----------

    private void stubSyntheticResidual() {
        when(registrar.syntheticServers()).thenReturn(java.util.Map.of("openviking",
            new McpToolRegistrar.SyntheticServer(
                HarnessAgentFactory.syntheticOpenvikingServerConfig(),
                () -> null)));
    }

    /** gen1 合成注入的名在 gen2 frontmatter 同名声明：新代建立成功后残留项必须清理（declared 独占该名） */
    @Test
    void reloadAgentShouldCleanSyntheticResidualWhenSameNameDeclared() throws IOException {
        stubSyntheticResidual();
        when(loader.load()).thenReturn(oafWithMcpServers("openviking"));

        service.reloadAgent();

        org.mockito.Mockito.verify(registrar).removeSyntheticServers(java.util.List.of("openviking"));
    }

    /** build 失败回滚：不达清理点，残留注册表保留——回滚窗口内旧 agent 合成句柄仍有看门狗自愈（评审 P2） */
    @Test
    void reloadAgentFailureShouldKeepSyntheticResidualForRollbackWindow() throws IOException {
        stubSyntheticResidual();
        when(loader.load()).thenReturn(oafWithMcpServers("openviking"));
        when(factory.build(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("build failed"));

        assertThrows(java.io.IOException.class, () -> service.reloadAgent());
        org.mockito.Mockito.verify(registrar, org.mockito.Mockito.never())
            .removeSyntheticServers(any());
    }

    @Test
    void fingerprintShouldDetectMcpConfigFileChanges() throws IOException {
        var mcpDir = tempDir.resolve("mcp-configs/server-a");
        Files.createDirectories(mcpDir);
        var configYaml = mcpDir.resolve("config.yaml");
        Files.writeString(configYaml, "connection:\n  type: sse\n  url: http://a\n");

        var fp1 = service.scanFingerprint();
        Mtc.tick(5);
        try {
            Files.writeString(configYaml, "connection:\n  type: sse\n  url: http://b\n");
        } catch (IOException e) {
            fail(e);
        }
        var fp2 = service.scanFingerprint();
        assertNotEquals(fp1, fp2, "config.yaml 内容变化应改变指纹");
    }

    /** mtime 分辨率等待（确保文件时间戳变化；失败直接中断测试） */
    private static final class Mtc {
        static void tick(long millis) {
            try {
                java.util.concurrent.TimeUnit.MILLISECONDS.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }
}
