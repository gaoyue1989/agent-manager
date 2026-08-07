package io.agentmanager.framework.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import static org.junit.jupiter.api.Assertions.*;

class McpToolRegistrarTest {

    @TempDir
    Path tempDir;

    private McpToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass"),
            tempDir.toString()
        );
        registrar = new McpToolRegistrar(props);
    }

    private OafConfig.McpServerConfig mcp(String server, String configDir) {
        return new OafConfig.McpServerConfig("vendor", server, "1.0.0", configDir, true);
    }

    private void writeConfigYaml(String server, String content) throws Exception {
        var dir = tempDir.resolve(server);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yaml"), content);
    }

    @Test
    void shouldBuildSseClient() throws Exception {
        writeConfigYaml("weather", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var wrapper = registrar.buildClient(mcp("weather", "weather"));
        assertNotNull(wrapper);
        assertEquals("weather", wrapper.getName());
        assertFalse(wrapper.isInitialized()); // 构建不连接
    }

    @Test
    void shouldBuildStdioClient() throws Exception {
        writeConfigYaml("local-py", """
            connection:
              type: stdio
              command: python
              args: ["mcp_server.py"]
            """);

        var wrapper = registrar.buildClient(mcp("local-py", "local-py"));
        assertNotNull(wrapper);
        assertEquals("local-py", wrapper.getName());
    }

    @Test
    void shouldBuildStreamableHttpClient() throws Exception {
        writeConfigYaml("http-api", """
            connection:
              type: streamableHttp
              url: https://api.example.com/mcp
            """);

        var wrapper = registrar.buildClient(mcp("http-api", "http-api"));
        assertNotNull(wrapper);
        assertEquals("http-api", wrapper.getName());
    }

    @Test
    void shouldBuildClientWithAuthEnvToken() throws Exception {
        writeConfigYaml("auth-mcp", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            auth:
              type: bearer
              token: ${TEST_MCP_TOKEN}
            """);

        // 环境变量未设置时替换为空字符串，构建不抛异常
        var wrapper = registrar.buildClient(mcp("auth-mcp", "auth-mcp"));
        assertNotNull(wrapper);
    }

    @Test
    void shouldBuildClientWithPlainToken() throws Exception {
        writeConfigYaml("auth-plain", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            auth:
              type: bearer
              token: static-token-123
            """);

        var wrapper = registrar.buildClient(mcp("auth-plain", "auth-plain"));
        assertNotNull(wrapper);
    }

    @Test
    void shouldReturnNullWhenConfigYamlMissing() {
        var wrapper = registrar.buildClient(mcp("missing", "missing"));
        assertNull(wrapper);
    }

    @Test
    void shouldReturnNullWhenConnectionSectionMissing() throws Exception {
        writeConfigYaml("no-conn", """
            vendor: block
            server: no-conn
            """);

        assertNull(registrar.buildClient(mcp("no-conn", "no-conn")));
    }

    @Test
    void shouldFallbackToServerDirWhenConfigDirNotExists() throws Exception {
        writeConfigYaml("fallback-server", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        // configDir 指向不存在的目录，回退到 server 名目录
        var wrapper = registrar.buildClient(mcp("fallback-server", "nonexistent-dir"));
        assertNotNull(wrapper);
        assertEquals("fallback-server", wrapper.getName());
    }

    @Test
    void shouldDetectReadOnlyPermission() throws Exception {
        writeConfigYaml("ro-server", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            permissions:
              read_only: true
            """);

        assertTrue(registrar.isReadOnlyConfigured(mcp("ro-server", "ro-server")));
    }

    @Test
    void shouldNotReadOnlyWhenPermissionAbsent() throws Exception {
        writeConfigYaml("rw-server", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        assertFalse(registrar.isReadOnlyConfigured(mcp("rw-server", "rw-server")));
    }

    @Test
    void shouldNotReadOnlyWhenPermissionFalse() throws Exception {
        writeConfigYaml("explicit-rw", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            permissions:
              read_only: false
            """);

        assertFalse(registrar.isReadOnlyConfigured(mcp("explicit-rw", "explicit-rw")));
    }

    private void writeActiveMcp(String server, String content) throws Exception {
        var dir = tempDir.resolve(server);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ActiveMCP.json"), content);
    }

    @Test
    void shouldLoadActiveMcpConfigWithEnabledFlags() throws Exception {
        writeActiveMcp("finance", """
            {
              "selectedTools": [
                {"name": "get_user_info", "enabled": true},
                {"name": "query_db", "enabled": true},
                {"name": "transfer_money", "enabled": false}
              ]
            }
            """);

        var config = registrar.loadActiveMcpConfig(mcp("finance", "finance"));

        assertNotNull(config);
        assertEquals(3, config.size());
        assertTrue(config.get("get_user_info"));
        assertTrue(config.get("query_db"));
        assertFalse(config.get("transfer_money"));
    }

    @Test
    void shouldReturnNullWhenActiveMcpMissing() throws Exception {
        writeConfigYaml("plain", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var config = registrar.loadActiveMcpConfig(mcp("plain", "plain"));
        assertNull(config);
    }

    @Test
    void shouldReturnNullWhenActiveMcpCorrupt() throws Exception {
        writeActiveMcp("corrupt", "{invalid json!!");

        var config = registrar.loadActiveMcpConfig(mcp("corrupt", "corrupt"));
        assertNull(config);
    }

    @Test
    void shouldDefaultEnabledTrueWhenFieldAbsent() throws Exception {
        writeActiveMcp("implicit-enabled", """
            {
              "selectedTools": [
                {"name": "get_weather"},
                {"name": "query_db", "enabled": false}
              ]
            }
            """);

        var config = registrar.loadActiveMcpConfig(mcp("implicit-enabled", "implicit-enabled"));

        assertNotNull(config);
        assertTrue(config.get("get_weather"));   // 无 enabled 字段默认 true
        assertFalse(config.get("query_db"));
    }
}
