package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/**
 * McpToolRegistrar reload 支撑能力单测：registerOne 循环体提取语义（与 registerAll 一致）、
 * clearServer 缓存清理、registeredWrappers 跟踪。
 */
class McpToolRegistrarReloadTest {

    @TempDir
    Path tempDir;

    private McpToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            tempDir.toString(),
            "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain,text/markdown,text/csv,application/pdf", 5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults()
        );
        registrar = new McpToolRegistrar(props);
    }

    private OafConfig.McpServerConfig mcp(String server) {
        return new OafConfig.McpServerConfig("vendor", server, "1.0.0", server, false);
    }

    @Test
    void registerOneShouldReturnNullWhenConfigYamlMissing() {
        var toolkit = new Toolkit();
        var result = registrar.registerOne(toolkit, mcp("no-such"));
        assertNull(result, "config.yaml 缺失时 fail-soft 返回 null");
        assertTrue(registrar.getRegisteredServerNames().isEmpty());
    }

    @Test
    void clearServerShouldRemoveCachesAndReportAbsence() throws Exception {
        var toolkit = mock(Toolkit.class);
        when(toolkit.removeMcpClient("weather")).thenReturn(reactor.core.publisher.Mono.empty());

        // 未注册时 clear 返回 false 且不抛
        assertFalse(registrar.clearServer(toolkit, "weather"));

        // 预置缓存条目（模拟 registerOne 装载结果）
        registrar.registerOneForTest(new Toolkit(), mcp("weather"));
        assertTrue(registrar.getRegisteredServerNames().contains("weather"));
        assertNotNull(registrar.getRegisteredWrapper("weather"));

        // clear：摘除 wrapper 跟踪 + toolkit.removeMcpClient 调用
        assertTrue(registrar.clearServer(toolkit, "weather"));
        assertFalse(registrar.getRegisteredServerNames().contains("weather"));
        assertNull(registrar.getRegisteredWrapper("weather"));
        // server 级配置缓存一并清理
        assertEquals(McpToolRegistrar.UiMapping.empty(), registrar.getUiMapping("weather"));
        assertTrue(registrar.getToolsByServer("weather").isEmpty());
    }

    @Test
    void clearServerShouldTolerateToolkitRemoveFailure() throws Exception {
        var toolkit = mock(Toolkit.class);
        when(toolkit.removeMcpClient("weather"))
            .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("conn closed")));

        registrar.registerOneForTest(new Toolkit(), mcp("weather"));
        // toolkit 移除失败不阻断缓存清理（返回 true，缓存已清）
        assertTrue(registrar.clearServer(toolkit, "weather"));
        assertFalse(registrar.getRegisteredServerNames().contains("weather"));
    }

    @Test
    void registerOneShouldReplaceWrapperTrackingOnReregister() throws Exception {
        var toolkit = new Toolkit();
        registrar.registerOneForTest(toolkit, mcp("weather"));
        var first = registrar.getRegisteredWrapper("weather");
        assertNotNull(first);

        // 重注册（reload 语义）：wrapper 跟踪替换为新实例
        registrar.registerOneForTest(toolkit, mcp("weather"));
        var second = registrar.getRegisteredWrapper("weather");
        assertNotNull(second);
        assertNotSame(first, second);
    }
}
