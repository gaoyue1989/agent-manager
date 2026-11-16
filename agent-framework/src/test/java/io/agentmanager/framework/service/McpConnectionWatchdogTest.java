package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Mono;

/**
 * MCP 连接看门狗单测（issue #348 对齐共享句柄架构）：探活健康不重建、失联对共享句柄
 * 原地换芯（不再对 toolkit 做 clearServer/registerBuiltClient 手术）、换芯成功联动资源
 * 代理缓存失效、缓存无句柄 compensate 复用 registerOne、reload 进行中让位、禁用语义。
 */
class McpConnectionWatchdogTest {

    private McpToolRegistrar registrar;
    private McpResourceProxy resourceProxy;
    private AgentRuntimeService agentRuntimeService;
    private OafConfigHolder oafConfigHolder;
    private OafReloadService oafReloadService;
    private HarnessAgent agent;
    private Toolkit toolkit;
    private McpClientWrapper handle;

    private McpConnectionWatchdog watchdog;

    private static final OafConfig.McpServerConfig MCP =
        new OafConfig.McpServerConfig("vendor", "biz-mcp", "1.0.0", "biz-mcp", false);

    @BeforeEach
    void setUp() {
        registrar = mock(McpToolRegistrar.class);
        resourceProxy = mock(McpResourceProxy.class);
        agentRuntimeService = mock(AgentRuntimeService.class);
        oafConfigHolder = mock(OafConfigHolder.class);
        oafReloadService = mock(OafReloadService.class);
        agent = mock(HarnessAgent.class);
        toolkit = mock(Toolkit.class);
        handle = mock(McpClientWrapper.class);

        when(oafReloadService.isReloadInProgress()).thenReturn(false);
        when(handle.getName()).thenReturn("biz-mcp");
        when(oafConfigHolder.get()).thenReturn(oafWithServer("biz-mcp"));

        watchdog = new McpConnectionWatchdog(registrar, resourceProxy, agentRuntimeService,
            oafConfigHolder, oafReloadService, new McpHealthTiming(30, 5));
    }

    private OafConfig oafWithServer(String server) {
        return new OafConfig(
            "member", "acme", "member", "1.0.0", "acme/member",
            "member agent", "@acme", "MIT",
            List.of("test"), "prompt",
            List.of(),
            List.of(new OafConfig.McpServerConfig("vendor", server, "1.0.0", server, false)),
            List.of(),
            List.of(),
            List.of(),
            null,
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            null, java.util.Map.of());
    }

    @Test
    void healthyHandleShouldNotRebuild() {
        when(registrar.sharedWrapper("biz-mcp")).thenReturn(handle);
        when(handle.listTools()).thenReturn(Mono.just(java.util.List.<io.modelcontextprotocol.spec.McpSchema.Tool>of()));

        watchdog.probeOne(MCP);

        verify(registrar, never()).rebuildSharedWrapper(anyString(), any());
        verify(registrar, never()).clearServer(any(), anyString());
        verify(resourceProxy, never()).evictClient(anyString());
        assertEquals(1, watchdog.stats()[0]);
        assertEquals(0, watchdog.stats()[1]);
    }

    @Test
    void deadHandleShouldRebuildInPlaceAndEvictResourceProxy() {
        when(registrar.sharedWrapper("biz-mcp")).thenReturn(handle);
        when(handle.listTools()).thenReturn(Mono.error(new java.net.ConnectException("conn refused")));
        when(registrar.rebuildSharedWrapper(eq("biz-mcp"), eq(MCP))).thenReturn(true);

        watchdog.probeOne(MCP);

        verify(registrar).rebuildSharedWrapper("biz-mcp", MCP);
        verify(resourceProxy).evictClient("biz-mcp");
        // 换芯自愈不再做 toolkit 手术：不摘注册、不重注册、不动单例 agent
        verify(registrar, never()).clearServer(any(), anyString());
        verify(registrar, never()).registerOne(any(), any());
        verify(agentRuntimeService, never()).getAgent();
        assertEquals(1, watchdog.stats()[1]);
    }

    @Test
    void rebuildFailureShouldNotEvictResourceProxy() {
        when(registrar.sharedWrapper("biz-mcp")).thenReturn(handle);
        when(handle.listTools()).thenReturn(Mono.error(new RuntimeException("dead")));
        when(registrar.rebuildSharedWrapper(eq("biz-mcp"), eq(MCP))).thenReturn(false);

        watchdog.probeOne(MCP);

        verify(registrar).rebuildSharedWrapper("biz-mcp", MCP);
        verify(resourceProxy, never()).evictClient(anyString());
        verify(registrar, never()).clearServer(any(), anyString());
    }

    /** compensate：缓存无句柄 + 单例 agent 存在 → registerOne 补建（完整注册语义，含 P0 驱逐） */
    @Test
    void missingHandleWithLiveAgentShouldCompensateViaRegisterOne() {
        when(registrar.sharedWrapper("biz-mcp")).thenReturn(null);
        when(agentRuntimeService.getAgent()).thenReturn(agent);
        when(agent.getToolkit()).thenReturn(toolkit);

        watchdog.probeOne(MCP);

        verify(registrar).registerOne(toolkit, MCP);
        verify(registrar, never()).rebuildSharedWrapper(anyString(), any());
        assertEquals(0, watchdog.stats()[0]); // 无句柄不探测
        assertEquals(1, watchdog.stats()[1]);
    }

    /** compensate：无单例 agent（纯会话隔离模式）→ 仅装缓存，新会话自然取用 */
    @Test
    void missingHandleWithoutAgentShouldCacheClientOnly() {
        when(registrar.sharedWrapper("biz-mcp")).thenReturn(null);
        when(agentRuntimeService.getAgent()).thenReturn(null);

        watchdog.probeOne(MCP);

        verify(registrar).sharedClient(MCP);
        verify(registrar, never()).registerOne(any(), any());
        assertEquals(1, watchdog.stats()[1]);
    }

    @Test
    void reloadInProgressShouldSkipWholeCycle() {
        when(oafReloadService.isReloadInProgress()).thenReturn(true);

        watchdog.probeAll();

        verify(registrar, never()).sharedWrapper(anyString());
        verify(registrar, never()).registerOne(any(), any());
        assertEquals(0, watchdog.stats()[0]);
    }

    @Test
    void missingOafConfigShouldSkipCycle() {
        when(oafConfigHolder.get()).thenReturn(null);

        watchdog.probeAll();

        verify(registrar, never()).sharedWrapper(anyString());
    }

    @Test
    void disabledIntervalShouldSkipProbe() {
        var disabled = new McpConnectionWatchdog(registrar, resourceProxy, agentRuntimeService,
            oafConfigHolder, oafReloadService, new McpHealthTiming(0, 5));

        disabled.probeAll();

        verify(registrar, never()).sharedWrapper(anyString());
        // 禁用语义下调度兜底周期为 1h（不产生 0/negative 的调度异常）
        var timing = new McpHealthTiming(0, 5);
        assertEquals(3_600_000L, timing.probeFixedDelayMs());
        assertFalse(timing.enabled());
    }

    /** 调度参数 bean：正常周期换算 ms（独立 bean 的原因见 McpHealthTiming javadoc） */
    @Test
    void timingShouldConvertSecondsToMillis() {
        var timing = new McpHealthTiming(30, 5);
        assertEquals(30_000L, timing.probeFixedDelayMs());
        assertTrue(timing.enabled());
        assertEquals(5, timing.probeTimeoutSeconds());
    }
}
