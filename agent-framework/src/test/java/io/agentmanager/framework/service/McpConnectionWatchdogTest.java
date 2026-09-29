package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
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
 * MCP 连接看门狗单测（PR #62 遗留 P0-1）：探活健康不重建、失联按 swap-on-success
 * 重建（build→clear→evict→register）、build 失败保留旧注册、注册失败关闭新连接、
 * reload 进行中让位、配置缺失跳过、禁用语义。
 */
class McpConnectionWatchdogTest {

    private McpToolRegistrar registrar;
    private McpResourceProxy resourceProxy;
    private AgentRuntimeService agentRuntimeService;
    private OafConfigHolder oafConfigHolder;
    private OafReloadService oafReloadService;
    private HarnessAgent agent;
    private Toolkit toolkit;
    private McpClientWrapper wrapper;
    private McpClientWrapper newWrapper;

    private McpConnectionWatchdog watchdog;

    @BeforeEach
    void setUp() {
        registrar = mock(McpToolRegistrar.class);
        resourceProxy = mock(McpResourceProxy.class);
        agentRuntimeService = mock(AgentRuntimeService.class);
        oafConfigHolder = mock(OafConfigHolder.class);
        oafReloadService = mock(OafReloadService.class);
        agent = mock(HarnessAgent.class);
        toolkit = mock(Toolkit.class);
        wrapper = mock(McpClientWrapper.class);
        newWrapper = mock(McpClientWrapper.class);

        when(oafReloadService.isReloadInProgress()).thenReturn(false);
        when(agentRuntimeService.getAgent()).thenReturn(agent);
        when(agent.getToolkit()).thenReturn(toolkit);
        when(wrapper.getName()).thenReturn("biz-mcp");
        when(registrar.getRegisteredServerNames()).thenReturn(java.util.Set.of("biz-mcp"));
        when(registrar.getRegisteredWrapper("biz-mcp")).thenReturn(wrapper);
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
    void healthyWrapperShouldNotRebuild() {
        when(wrapper.listTools()).thenReturn(Mono.just(java.util.List.<io.modelcontextprotocol.spec.McpSchema.Tool>of()));

        watchdog.probeOne("biz-mcp");

        verify(registrar, never()).buildClientForReload(any());
        verify(registrar, never()).clearServer(any(), anyString());
        assertEquals(1, watchdog.stats()[0]);
        assertEquals(0, watchdog.stats()[1]);
    }

    @Test
    void deadConnectionShouldRebuildViaSwapSequence() {
        when(wrapper.listTools()).thenReturn(Mono.error(new java.net.ConnectException("conn refused")));
        when(registrar.buildClientForReload(any())).thenReturn(newWrapper);

        watchdog.probeOne("biz-mcp");

        var mcp = new OafConfig.McpServerConfig("vendor", "biz-mcp", "1.0.0", "biz-mcp", false);
        verify(registrar).buildClientForReload(mcp);
        verify(registrar).clearServer(toolkit, "biz-mcp");
        verify(resourceProxy).evictClient("biz-mcp");
        verify(registrar).registerBuiltClient(toolkit, newWrapper, mcp);
        verify(registrar, never()).closeWrapperQuietly(any());
        assertEquals(1, watchdog.stats()[1]);
    }

    @Test
    void buildFailureShouldKeepOldRegistration() {
        when(wrapper.listTools()).thenReturn(Mono.error(new RuntimeException("dead")));
        when(registrar.buildClientForReload(any())).thenReturn(null);

        watchdog.probeOne("biz-mcp");

        verify(registrar, never()).clearServer(any(), anyString());
        verify(registrar, never()).registerBuiltClient(any(), any(), any());
    }

    @Test
    void registerFailureShouldCloseNewWrapper() {
        when(wrapper.listTools()).thenReturn(Mono.error(new RuntimeException("dead")));
        when(registrar.buildClientForReload(any())).thenReturn(newWrapper);
        when(registrar.registerBuiltClient(any(), any(), any()))
            .thenThrow(new RuntimeException("register failed"));

        watchdog.probeOne("biz-mcp");

        verify(registrar).clearServer(toolkit, "biz-mcp");
        verify(registrar).closeWrapperQuietly(newWrapper);
    }

    @Test
    void missingServerConfigShouldSkipRebuild() {
        when(wrapper.listTools()).thenReturn(Mono.error(new RuntimeException("dead")));
        when(oafConfigHolder.get()).thenReturn(oafWithServer("other-server"));

        watchdog.probeOne("biz-mcp");

        verify(registrar, never()).buildClientForReload(any());
    }

    @Test
    void reloadInProgressShouldSkipWholeCycle() {
        when(oafReloadService.isReloadInProgress()).thenReturn(true);

        watchdog.probeAll();

        verify(registrar, never()).getRegisteredWrapper(anyString());
        assertEquals(0, watchdog.stats()[0]);
    }

    @Test
    void disabledIntervalShouldSkipProbe() {
        var disabled = new McpConnectionWatchdog(registrar, resourceProxy, agentRuntimeService,
            oafConfigHolder, oafReloadService, new McpHealthTiming(0, 5));

        disabled.probeAll();

        verify(registrar, never()).getRegisteredWrapper(anyString());
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
