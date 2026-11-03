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
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;

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

        var factory = new HarnessAgentFactory(props, ws, mcp, List.of(), bridge, null, null);
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
        return assemblyFactory(remoteHeadersJson, AgentManagerProperties.HarnessConfig.defaults());
    }

    /** 同口径工厂，但 Harness 运行时参数可指定（用于断言 compaction 等配置真的进了构建产物） */
    private HarnessAgentFactory assemblyFactory(String remoteHeadersJson,
                                                AgentManagerProperties.HarnessConfig harness)
            throws Exception {
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
            harness,
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
            mock(RemoteConfirmBridge.class), null, null);
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

    /**
     * token 触发阈值必须真的进入构建产物（MCP 大量返回场景的回归锚点）：
     * 装配层漏调 {@code triggerTokens()} 时 SDK 会退回动态模式/默认值，消息数仍是唯一有效阈值。
     */
    @Test
    void compactionShouldBeWiredWithTokenThresholdFromHarnessSettings() throws Exception {
        var harness = new AgentManagerProperties.HarnessConfig(
            20, 30, 180, 30, true, 10, 8000, 60,
            30, 12345, 4321, 10, true, true, 10, 2, 30000L, 600000L, 1800000L);
        var store = mock(DistributedStore.class);
        when(store.baseStore())
            .thenReturn(new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
        when(store.agentStateStore())
            .thenReturn(mock(io.agentscope.core.state.AgentStateStore.class));

        var agent = assemblyFactory(null, harness)
            .build(oafWithEndpoint(""), store, new LLMLogger(), null, null,
                mock(ModelCatalog.class), null);

        var compaction = agent.getCompactionHook();
        assertNotNull(compaction, "compaction 已配置时必须产出 CompactionMiddleware");

        Field configField = CompactionMiddleware.class.getDeclaredField("config");
        configField.setAccessible(true);
        var cfg = (CompactionConfig) configField.get(compaction);
        assertEquals(12345, cfg.getTriggerTokens(), "AGENT_COMPACTION_TRIGGER_TOKENS 必须接线");
        assertEquals(4321, cfg.getReserved(), "AGENT_COMPACTION_RESERVED_TOKENS 必须接线");
        assertEquals(30, cfg.getTriggerMessages(), "消息数阈值保持并存（二者任一命中即压缩）");
    }

    /**
     * 停止链路装配守卫（turn-stop-design §4）：{@link TurnInterruptMiddleware} 是把停止信号
     * 登记进 {@link TurnControlRegistry} 的唯一入口（registry.register 唯一调用点），缺失即
     * stop 静默 no-op（端点仍返 202，watcher 轮询落空）——「类存在但从未注册」的断链形态
     * 本仓库已发生过一次（见类注释 RemoteSpawnCaptureMiddleware 先例）。
     *
     * <p>接线断言为什么不可省：build() 注册点对 turnControlRegistry 字段无 null 守卫（字段为
     * null 时中间件照样进链，运行期 onAgent 判空跳过），仅断言「链上存在实例」防不住接线退化；
     * registry 字段是 @Autowired(required=false) 字段注入（非构造参数），单测直接 new 时恒 null，
     * 须反射置入后再 build。
     */
    @Test
    void buildShouldRegisterTurnInterruptMiddlewareWiredToInjectedRegistry() throws Exception {
        var oaf = mock(OafConfig.class);
        when(oaf.name()).thenReturn("test-agent");
        when(oaf.systemPrompt()).thenReturn("prompt");
        when(oaf.runtimeConfig()).thenReturn(new OafConfig.RuntimeConfig(
            0.7, 4096, false, "default", java.util.Map.of()));

        var ws = mock(WorkspaceInitializer.class);
        when(ws.initialize(any(java.nio.file.Path.class), any(OafConfig.class)))
            .thenReturn(java.nio.file.Files.createTempDirectory("turn-interrupt-assembly"));
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
            java.nio.file.Files.createTempDirectory("turn-interrupt-config").toString(),
            "", new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20,
                "image/*,text/plain,text/markdown,text/csv,application/pdf",
                5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults(),
            AgentManagerProperties.AgentProtocolSettings.defaults());

        var factory = new HarnessAgentFactory(props, ws, mcp, List.of(),
            mock(RemoteConfirmBridge.class), null, null);
        // turnControlRegistry 是字段注入（@Autowired(required=false)），单测直接 new 恒 null；
        // 反射置入真实 registry 后 build，才能断言接线（中间件持有的就是注入实例）
        var registry = new TurnControlRegistry();
        Field factoryRegistryField = HarnessAgentFactory.class.getDeclaredField("turnControlRegistry");
        factoryRegistryField.setAccessible(true);
        factoryRegistryField.set(factory, registry);

        var agent = factory.build(oaf, store, new LLMLogger(), null, null,
            mock(io.agentmanager.framework.service.ModelCatalog.class), null);

        var middlewares = middlewaresOf(agent);
        var turnInterrupt = middlewares.stream()
            .filter(TurnInterruptMiddleware.class::isInstance)
            .map(TurnInterruptMiddleware.class::cast)
            .findFirst().orElse(null);
        assertNotNull(turnInterrupt,
            "TurnInterruptMiddleware 必须注册进 builder 链（停止信号登记唯一入口，"
                + "缺失即 POST /threads/{sid}/stop 静默 no-op）");

        // 核心接线断言：中间件持有的必须是注入的 registry 实例（注册点无 null 守卫，
        // 「链上存在实例」不接线也能绿——assertSame 才防得住退化）
        Field registryField = TurnInterruptMiddleware.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        assertSame(registry, registryField.get(turnInterrupt),
            "TurnInterruptMiddleware 必须接线到注入的 TurnControlRegistry（断链即 watcher 轮询落空）");

        // 同链 sessionKeyResolver 共享（build() 内部 new 的同一实例）：turn 级 memo 共享不变量——
        // 若 TurnInterruptMiddleware 拿到独立 resolver，Channel 链路键改写解析分叉会误杀其他会话
        Field skrField = TurnInterruptMiddleware.class.getDeclaredField("sessionKeyResolver");
        skrField.setAccessible(true);
        var skrInTurnInterrupt = skrField.get(turnInterrupt);
        assertNotNull(skrInTurnInterrupt, "sessionKeyResolver 必须接线（缺省回退 ctx.getSessionId() 误杀）");
        var capture = middlewares.stream()
            .filter(RemoteSpawnCaptureMiddleware.class::isInstance)
            .map(RemoteSpawnCaptureMiddleware.class::cast)
            .findFirst().orElse(null);
        if (capture != null) {
            Field captureSkrField = RemoteSpawnCaptureMiddleware.class.getDeclaredField("sessionKeyResolver");
            captureSkrField.setAccessible(true);
            assertSame(skrInTurnInterrupt, captureSkrField.get(capture),
                "同链中间件必须共享同一 SessionKeyResolver 实例（turn 级 memo 共享）");
        }

        // order=3：最外层先于任何可能短路的中间件完成登记
        assertEquals(3, turnInterrupt.order());
    }

    @Test
    void skillFilterMiddlewareMustComeBeforeDisabledMiddleware() throws Exception {
        // session-skill-list-design §4 顺序约束：本轮白名单先立，禁用中间件再以
        // existing.overlay(disable(...)) 从中剔除——顺序颠倒会让禁用集丢失或白名单被冲掉
        var oaf = mock(OafConfig.class);
        when(oaf.name()).thenReturn("test-agent");
        when(oaf.systemPrompt()).thenReturn("prompt");
        when(oaf.runtimeConfig()).thenReturn(new OafConfig.RuntimeConfig(
            0.7, 4096, false, "default", java.util.Map.of()));

        var ws = mock(WorkspaceInitializer.class);
        when(ws.initialize(any(java.nio.file.Path.class), any(OafConfig.class)))
            .thenReturn(java.nio.file.Files.createTempDirectory("skill-filter-assembly"));
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
            java.nio.file.Files.createTempDirectory("skill-filter-config").toString(),
            "", new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20,
                "image/*,text/plain,text/markdown,text/csv,application/pdf",
                5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults(),
            AgentManagerProperties.AgentProtocolSettings.defaults());

        var factory = new HarnessAgentFactory(props, ws, mcp, List.of(),
            mock(RemoteConfirmBridge.class), mock(UserSkillService.class),
            mock(SkillManageService.class));
        var agent = factory.build(oaf, store, new LLMLogger(), null, null,
            mock(io.agentmanager.framework.service.ModelCatalog.class), null);

        var middlewares = middlewaresOf(agent);
        int filterIdx = -1;
        int disabledIdx = -1;
        for (int i = 0; i < middlewares.size(); i++) {
            var mw = middlewares.get(i);
            if (mw instanceof SkillFilterMiddleware) filterIdx = i;
            if (mw instanceof SkillDisabledFilterMiddleware) disabledIdx = i;
        }
        assertTrue(filterIdx >= 0, "SkillFilterMiddleware 必须注册进 builder 链");
        assertTrue(disabledIdx >= 0, "SkillDisabledFilterMiddleware 必须注册进 builder 链");
        assertTrue(filterIdx < disabledIdx,
            "技能白名单中间件必须排在禁用中间件之前（实际 " + filterIdx + " vs " + disabledIdx + "）");
    }
}
