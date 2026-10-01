package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.McpToolRegistrar.PermissionRuleResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * HarnessAgentFactory 装配级测试：RemoteSpawnCaptureMiddleware 必须注册进 builder 链
 * （travel-fulfillment §5.5，RemoteConfirmBridge 在途任务登记唯一入口）——此前曾出现
 * 「中间件类存在但从未注册」的断链（在途登记恒空 → 快照轮询永不生效），此测试为回归锚点。
 *
 * <p>全量构建真实 HarnessAgent（与 AgentScopeConfigTest 的 buildAgentToolNames 同口径打桩：
 * 真实 InMemoryStore 兜底 DistributedStore、mock WorkspaceInitializer/McpToolRegistrar），
 * 经 HarnessAgent 私有 delegate（ReActAgent.getMiddlewares()）断言注册，并进一步断言
 * 中间件持有的就是注入的 RemoteConfirmBridge 实例（防注入断链退化为 null 桥）。
 */
class HarnessAgentFactoryMiddlewareAssemblyTest {

    /** 经 HarnessAgent.delegate 取运行时中间件链（SDK 未暴露，反射读私有字段） */
    private static List<MiddlewareBase> middlewaresOf(HarnessAgent agent) throws Exception {
        var delegateField = HarnessAgent.class.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        var reactAgent = (ReActAgent) delegateField.get(agent);
        return reactAgent.getMiddlewares();
    }

    @Test
    void buildShouldRegisterSpawnCaptureMiddlewareWiredToInjectedBridge() throws Exception {
        var oaf = mock(OafConfig.class);
        when(oaf.name()).thenReturn("test-agent");
        when(oaf.systemPrompt()).thenReturn("prompt");
        when(oaf.runtimeConfig()).thenReturn(new OafConfig.RuntimeConfig(
            0.7, 4096, false, "default", java.util.Map.of()));

        var ws = mock(WorkspaceInitializer.class);
        when(ws.initialize(any(java.nio.file.Path.class), any(OafConfig.class)))
            .thenReturn(java.nio.file.Files.createTempDirectory("spawn-capture-assembly"));
        var mcp = mock(McpToolRegistrar.class);
        when(mcp.collectPermissionRules(oaf))
            .thenReturn(new PermissionRuleResult(
                io.agentscope.core.permission.PermissionMode.DEFAULT,
                java.util.Map.of(), java.util.Set.of()));

        var store = mock(DistributedStore.class);
        when(store.baseStore())
            .thenReturn(new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
        when(store.agentStateStore())
            .thenReturn(mock(io.agentscope.core.state.AgentStateStore.class));

        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig(
                "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig(
                "jdbc:mysql://127.0.0.1:3307/agent_manager_test", "u", "p", "agent_manager_test"),
            java.nio.file.Files.createTempDirectory("spawn-capture-config").toString(),
            "", new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20,
                "image/*,text/plain,text/markdown,text/csv,application/pdf",
                5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults(),
            AgentManagerProperties.AgentProtocolSettings.defaults());
        var bridge = mock(RemoteConfirmBridge.class);

        var factory = new HarnessAgentFactory(props, ws, mcp, List.of(), bridge);
        var agent = factory.build(oaf, store, new LLMLogger(), null, null,
            mock(io.agentmanager.framework.service.ModelCatalog.class), null);

        var middlewares = middlewaresOf(agent);
        var capture = middlewares.stream()
            .filter(RemoteSpawnCaptureMiddleware.class::isInstance)
            .map(RemoteSpawnCaptureMiddleware.class::cast)
            .findFirst().orElse(null);
        assertNotNull(capture,
            "RemoteSpawnCaptureMiddleware 必须注册进 builder 链（在途任务登记唯一入口，"
                + "缺失即快照轮询断链）");

        // 注入的桥必须真实接线到中间件（防注册存在但桥为 null/另一实例的退化）
        Field bridgeField = RemoteSpawnCaptureMiddleware.class.getDeclaredField("bridge");
        bridgeField.setAccessible(true);
        assertSame(bridge, bridgeField.get(capture));

        // 同链上保留身份规范化中间件（F10 前置语义），防后续重构静默摘除
        assertTrue(middlewares.stream().anyMatch(RemoteUserIdMiddleware.class::isInstance),
            "RemoteUserIdMiddleware 必须同链保留");
    }

    // ===== 远程子 agent 声明：映射正确 ≠ 注册进构建产物 =====

    private static final String BOOKING_ENDPOINT =
        "http://booking.agent-platform.svc.cluster.local:8100";

    /** 真实 OafConfig（booking 带远程 endpoint，同 RemoteConfirmBridgeMultiReplicaTest 口径） */
    private static OafConfig oafWithEndpoint(String endpoint) {
        return new OafConfig(
            "trip-lead", "acme", "trip-lead", "1.0.0", "acme/trip-lead",
            "lead", "@acme", "MIT", List.of(), "you are a planner.",
            List.of(), List.of(),
            List.of(new OafConfig.SubAgentConfig("internal", "booking", "1.0.0",
                "订票专员", List.of(), true, endpoint)),
            List.of(), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            null, java.util.Map.of());
    }

    /** 组装与首条用例同口径的 factory（WorkspaceInitializer / McpToolRegistrar / DistributedStore 打桩） */
    private HarnessAgentFactory assemblyFactory(String remoteHeadersJson) throws Exception {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig(
                "k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig(
                "jdbc:mysql://127.0.0.1:3307/agent_manager_test", "u", "p", "agent_manager_test"),
            java.nio.file.Files.createTempDirectory("remote-subagent-config").toString(),
            "", new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20,
                "image/*,text/plain,text/markdown,text/csv,application/pdf",
                5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults(),
            new AgentManagerProperties.AgentProtocolSettings(
                true, "tok", "", 7, 24, 5, remoteHeadersJson, true, 120, "memory"));
        var ws = mock(WorkspaceInitializer.class);
        when(ws.initialize(any(java.nio.file.Path.class), any(OafConfig.class)))
            .thenReturn(java.nio.file.Files.createTempDirectory("remote-subagent-ws"));
        var mcp = mock(McpToolRegistrar.class);
        when(mcp.collectPermissionRules(any(OafConfig.class)))
            .thenReturn(new PermissionRuleResult(
                io.agentscope.core.permission.PermissionMode.DEFAULT,
                java.util.Map.of(), java.util.Set.of()));
        return new HarnessAgentFactory(props, ws, mcp, List.of(),
            mock(RemoteConfirmBridge.class));
    }

    /**
     * 断链回归锚点：{@code buildRemoteSubagentDeclarations} 映射正确并不代表声明进了 agent。
     * 本仓库曾发生「类存在但从未注册」的断链（同 RemoteSpawnCaptureMiddleware 那次），
     * 此处从**构建产物**（getSubagentAgentManager().getDeclaration）反查注册结果。
     */
    @Test
    void remoteSubagentDeclarationShouldBeRegisteredIntoBuiltAgent() throws Exception {
        var oaf = oafWithEndpoint(BOOKING_ENDPOINT);
        var store = mock(DistributedStore.class);
        when(store.baseStore())
            .thenReturn(new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
        when(store.agentStateStore())
            .thenReturn(mock(io.agentscope.core.state.AgentStateStore.class));

        var agent = assemblyFactory("{\"X-Service-Token\":\"tok-1\"}")
            .build(oaf, store, new LLMLogger(), null, null, mock(ModelCatalog.class), null);

        var declared = agent.getSubagentAgentManager().getDeclaration("booking");
        assertTrue(declared.isPresent(),
            "带 endpoint 的 booking 声明必须真的注册进构建产物（断链即 agent_spawn 找不到子 agent）");
        assertEquals(BOOKING_ENDPOINT, declared.get().getUrl());
        assertEquals("tok-1", declared.get().getHeaders().get("X-Service-Token"),
            "AGENT_REMOTE_HEADERS_JSON 必须随声明注册（服务间认证缺失则远程调用被拒）");
    }

    /** endpoint 为空的本地 subagent 不进远程声明面（维持 workspace subagents/*.md 链路） */
    @Test
    void subAgentWithoutEndpointShouldNotRegisterRemoteDeclaration() throws Exception {
        var oaf = oafWithEndpoint("");
        var store = mock(DistributedStore.class);
        when(store.baseStore())
            .thenReturn(new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
        when(store.agentStateStore())
            .thenReturn(mock(io.agentscope.core.state.AgentStateStore.class));

        var agent = assemblyFactory(null)
            .build(oaf, store, new LLMLogger(), null, null, mock(ModelCatalog.class), null);

        assertTrue(agent.getSubagentAgentManager().getDeclaration("booking").isEmpty(),
            "endpoint 为空者不应被注册为远程声明");
    }
}
