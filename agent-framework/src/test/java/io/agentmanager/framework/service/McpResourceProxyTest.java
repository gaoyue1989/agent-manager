package io.agentmanager.framework.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.McpResourceProxy.McpProxyException;
import io.agentmanager.framework.service.McpResourceProxy.NeedsConfirmException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 资源与调用代理测试（MCP Apps 扩展阶段一）。
 * 校验逻辑不连网（scheme/uri 集合/注册状态/权限），连接失败路径 mock 兜底。
 */
class McpResourceProxyTest {

    @TempDir
    Path tempDir;

    private McpResourceProxy proxy;
    private McpToolRegistrar registrar;
    private OafConfig oafConfig;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            tempDir.toString()
        );
        registrar = new McpToolRegistrar(props);
        oafConfig = mock(OafConfig.class);
        when(oafConfig.mcpServers()).thenReturn(List.of(new OafConfig.McpServerConfig("v", "weather", "1.0", "weather", true)));
        proxy = new McpResourceProxy(oafConfig, registrar);
    }

    private void writeConfigYaml(String server, String content) throws Exception {
        var dir = tempDir.resolve(server);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yaml"), content);
    }

    private void seedUiTool(String server, String toolName, String uri) {
        var mapping = new McpToolRegistrar.UiMapping(
            Map.of(toolName, uri), Map.of(), McpToolRegistrar.UiCsp.empty());
        var wrapper = org.mockito.Mockito.mock(io.agentscope.core.tool.mcp.McpClientWrapper.class);
        when(wrapper.listTools()).thenReturn(reactor.core.publisher.Mono.just(List.of(
            new io.modelcontextprotocol.spec.McpSchema.Tool(toolName, "", "desc", null, null, null, null))));
        registrar.recordRegisteredTools(wrapper, server, mapping);
    }

    // ===== 资源读取校验 =====

    @Test
    void shouldRejectNonUiScheme() {
        seedUiTool("weather", "get_weather", "ui://weather/mcp-app.html");
        assertThrows(McpProxyException.class, () -> proxy.readUiResource("weather", "https://evil.example.com/x.html"));
    }

    @Test
    void shouldRejectUriNotInDeclaredSet() {
        seedUiTool("weather", "get_weather", "ui://weather/mcp-app.html");
        assertThrows(McpProxyException.class, () -> proxy.readUiResource("weather", "ui://weather/other.html"));
    }

    @Test
    void shouldRejectUnknownServer() {
        assertThrows(McpProxyException.class, () -> proxy.readUiResource("ghost", "ui://weather/mcp-app.html"));
    }

    @Test
    void shouldRejectBlankServer() {
        assertThrows(McpProxyException.class, () -> proxy.readUiResource("", "ui://weather/mcp-app.html"));
    }

    // ===== 工具调用校验 =====

    @Test
    void shouldRejectUnregisteredTool() {
        seedUiTool("weather", "get_weather", "ui://weather/mcp-app.html");
        assertThrows(McpProxyException.class,
            () -> proxy.callTool("weather", "not_registered", Map.of(), false));
    }

    @Test
    void shouldRejectNullArguments() {
        seedUiTool("weather", "get_weather", "ui://weather/mcp-app.html");
        assertThrows(McpProxyException.class,
            () -> proxy.callTool("weather", "get_weather", null, false));
    }

    @Test
    void shouldAllowEmptyArgumentsForParameterlessTool() throws Exception {
        seedUiTool("weather", "get_weather", "ui://weather/mcp-app.html");
        // 无连接时走到 getOrCreateClient 才失败（502），此处校验空参数合法通过
        try {
            proxy.callTool("weather", "get_weather", Map.of(), false);
            fail("应因无连接而 502");
        } catch (McpProxyException e) {
            assertEquals(502, e.status());
        }
    }

    @Test
    void shouldRejectDeniedTool() {
        seedUiTool("weather", "delete_weather", "ui://weather/mcp-app.html");
        writePermission("weather", Map.of("delete_weather", "deny"));
        var e = assertThrows(McpProxyException.class,
            () -> proxy.callTool("weather", "delete_weather", Map.of(), false));
        assertEquals(403, e.status());
    }

    @Test
    void shouldRequireConfirmForAskTool() {
        seedUiTool("weather", "write_weather", "ui://weather/mcp-app.html");
        writePermission("weather", Map.of("write_weather", "ask"));
        assertThrows(NeedsConfirmException.class,
            () -> proxy.callTool("weather", "write_weather", Map.of("data", "x"), false));
        // confirmed=true 放行到连接层（无连接 502 而非 403）
        try {
            proxy.callTool("weather", "write_weather", Map.of("data", "x"), true);
            fail("应因无连接而 502");
        } catch (McpProxyException e) {
            assertEquals(502, e.status());
        }
    }

    @Test
    void shouldRejectDestructiveToolOnReadOnlyServer() {
        seedUiTool("weather", "delete_weather", "ui://weather/mcp-app.html");
        writeReadOnly("weather", true);
        registrar.markDestructiveHintForTest("weather", "delete_weather");
        var e = assertThrows(McpProxyException.class,
            () -> proxy.callTool("weather", "delete_weather", Map.of(), false));
        assertEquals(403, e.status());
    }

    // ===== 辅助 =====

    private void writePermission(String server, Map<String, String> perms) {
        registrar.setToolPermissionsForTest(server, perms);
    }

    private void writeReadOnly(String server, boolean readOnly) {
        registrar.setReadOnlyForTest(server, readOnly);
    }
}