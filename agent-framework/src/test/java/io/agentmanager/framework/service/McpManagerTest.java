package io.agentmanager.framework.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.model.OafConfig.McpServerConfig;

import static org.junit.jupiter.api.Assertions.*;

class McpManagerTest {

    @TempDir
    Path configDir;

    private McpManager manager;
    private McpToolRegistrar mcpToolRegistrar;

    @BeforeEach
    void setUp() {
        mcpToolRegistrar = org.mockito.Mockito.mock(McpToolRegistrar.class);
        manager = new McpManager(configDir, mcpToolRegistrar);
    }

    private McpServerConfig server(String name, String configDir) {
        return new McpServerConfig("weather", name, "1.0.0", configDir, true);
    }

    @Test
    void loadConfigsShouldReturnConfigsForExistingDirs() throws Exception {
        var dir = configDir.resolve("mcp-configs/weather");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yaml"), "connection:\n  type: streamableHttp\n  url: http://localhost:8811/mcp\n");

        var result = manager.loadConfigs(List.of(server("weather-service", "mcp-configs/weather")));

        assertEquals(1, result.size());
        assertEquals("weather", result.get(0).get("vendor"));
        assertEquals("weather-service", result.get(0).get("server"));
        assertTrue(result.get(0).get("required") instanceof Boolean b && b);
    }

    @Test
    void loadConfigsShouldSkipMissingDirs() {
        var result = manager.loadConfigs(List.of(server("ghost", "mcp-configs/ghost")));

        assertTrue(result.isEmpty());
    }

    @Test
    void loadSingleConfigShouldFallbackToServerNameWhenConfigDirMissing() throws Exception {
        Files.createDirectories(configDir.resolve("weather-service"));
        Files.writeString(configDir.resolve("weather-service/config.yaml"), "connection:\n  type: sse\n  url: http://localhost:8811/sse\n");

        var result = manager.loadConfigs(List.of(server("weather-service", "mcp-configs/does-not-exist")));

        assertEquals(1, result.size());
        assertEquals("weather-service", result.get(0).get("server"));
    }

    @Test
    void loadConfigsShouldReadActiveMcpJson() throws Exception {
        var dir = configDir.resolve("mcp-configs/weather");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ActiveMCP.json"),
            "{\"selectedTools\":[{\"name\":\"get_weather\"}]}");

        var result = manager.loadConfigs(List.of(server("weather-service", "mcp-configs/weather")));

        @SuppressWarnings("unchecked")
        var tools = (Map<String, Object>) result.get(0).get("tools");
        assertEquals(1, ((List<?>) tools.get("selectedTools")).size());
    }

    @Test
    void loadConfigsShouldTolerateCorruptActiveMcpJson() throws Exception {
        var dir = configDir.resolve("mcp-configs/weather");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ActiveMCP.json"), "{invalid json!!");

        var result = manager.loadConfigs(List.of(server("weather-service", "mcp-configs/weather")));

        assertEquals(1, result.size());
        // 损坏 JSON 时 tools 为空 Map（安全默认值），不再抛异常
        assertTrue(result.get(0).containsKey("tools"));
        assertEquals(Map.of(), result.get(0).get("tools"));
    }

    @Test
    void loadConfigsShouldTolerateCorruptYaml() throws Exception {
        var dir = configDir.resolve("mcp-configs/weather");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yaml"), "connection: [unclosed");

        var result = manager.loadConfigs(List.of(server("weather-service", "mcp-configs/weather")));

        assertEquals(1, result.size());
        assertFalse(result.get(0).containsKey("connection"));
    }

    @Test
    void loadConfigsShouldSkipYamlWithoutConnectionSection() throws Exception {
        var dir = configDir.resolve("mcp-configs/weather");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yaml"), "server: weather-service\nvendor: weather\n");

        var result = manager.loadConfigs(List.of(server("weather-service", "mcp-configs/weather")));

        assertEquals(1, result.size());
        assertFalse(result.get(0).containsKey("connection"));
    }

    @Test
    void getMcpSummariesShouldSummarizeConnectionAndTools() {
        var mcpConfigs = List.of(Map.<String, Object>of(
            "server", "weather-service",
            "vendor", "weather",
            "connection", Map.of("type", "streamableHttp", "url", "http://localhost:8811/mcp"),
            "tools", Map.of("selectedTools", List.of(Map.of("name", "a"), Map.of("name", "b")))
        ));

        // mock 注册缓存返回 2 个真实注册工具
        org.mockito.Mockito.when(mcpToolRegistrar.getToolsByServer("weather-service"))
            .thenReturn(List.of(
                new McpToolRegistrar.ToolInfo("a", "mcp__weather-service__a", "", "weather-service"),
                new McpToolRegistrar.ToolInfo("b", "mcp__weather-service__b", "", "weather-service")
            ));

        var summaries = manager.getMcpSummaries(mcpConfigs);

        assertEquals(1, summaries.size());
        var s = summaries.get(0);
        assertEquals("weather-service", s.get("server"));
        assertEquals("streamableHttp", s.get("connection_type"));
        assertEquals(2, s.get("tool_count"));
    }

    @Test
    void getMcpSummariesShouldHandleEmptyAndMissingFields() {
        var mcpConfigs = List.of(Map.<String, Object>of());

        var summaries = manager.getMcpSummaries(mcpConfigs);

        assertEquals(1, summaries.size());
        assertEquals("unknown", summaries.get(0).get("server"));
        assertEquals("N/A", summaries.get(0).get("connection_type"));
        assertEquals(0, summaries.get(0).get("tool_count"));
    }
}