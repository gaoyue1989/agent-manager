package io.agentmanager.framework.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.mcp.McpUserContextMiddleware;
import io.agentmanager.framework.mcp.UserScopedMcpClientWrapper;
import io.agentmanager.framework.model.OafConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * userHeaders 配置解析与 buildClient 装饰分支测试（T1/T3）。
 */
class McpToolRegistrarUserHeadersTest {

    @TempDir
    Path tempDir;

    private McpToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0, "", null),
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

    private OafConfig.McpServerConfig mcp(String server, String configDir) {
        return new OafConfig.McpServerConfig("vendor", server, "1.0.0", configDir, true);
    }

    private void writeConfigYaml(String server, String content) throws Exception {
        var dir = tempDir.resolve(server);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yaml"), content);
    }

    // ===== parseUserHeaders =====

    @Test
    void shouldParseUserHeadersWithDenyDefault() {
        var rule = registrar.parseUserHeaders(
            Map.of("userHeaders", Map.of(
                "headers", Map.of("X-User-Id", "userId", "Authorization", "user_token"))),
            "srv");

        assertNotNull(rule);
        assertEquals(2, rule.headers().size());
        assertEquals("userId", rule.headers().get("X-User-Id"));
        assertTrue(rule.denyOnMissing(), "缺省应为 deny(fail-closed)");
    }

    @Test
    void shouldParsePassthroughOnMissing() {
        var rule = registrar.parseUserHeaders(
            Map.of("userHeaders", Map.of(
                "headers", Map.of("X-User-Id", "userId"),
                "on-missing", "passthrough")),
            "srv");

        assertNotNull(rule);
        assertFalse(rule.denyOnMissing());
    }

    @Test
    void shouldReturnNullWhenSectionAbsentOrHeadersEmpty() {
        assertNull(registrar.parseUserHeaders(Map.of(), "srv"));
        assertNull(registrar.parseUserHeaders(Map.of("userHeaders", Map.of()), "srv"));
        assertNull(registrar.parseUserHeaders(
            Map.of("userHeaders", Map.of("headers", Map.of())), "srv"));
    }

    @Test
    void shouldIgnoreInvalidEntriesAndReturnNullWhenAllInvalid() {
        // 非法条目（非 String 值）被过滤；全部非法 → null
        assertNull(registrar.parseUserHeaders(
            Map.of("userHeaders", Map.of("headers", Map.of("X-User-Id", 42))), "srv"));

        var rule = registrar.parseUserHeaders(
            Map.of("userHeaders", Map.of("headers", Map.of(
                "X-User-Id", "userId",
                "Bad", 42))),
            "srv");
        assertNotNull(rule);
        assertEquals(1, rule.headers().size());
    }

    // ===== buildClient 装饰分支 =====

    @Test
    void shouldWrapWithUserScopedWhenHttpAndUserHeadersConfigured() throws Exception {
        writeConfigYaml("scoped-mcp", """
            connection:
              type: streamableHttp
              url: https://mcp.example.com/mcp
            auth:
              type: bearer
              token: static-token
            userHeaders:
              headers:
                X-User-Id: userId
            """);

        var wrapper = registrar.buildClient(mcp("scoped-mcp", "scoped-mcp"));
        assertNotNull(wrapper);
        assertInstanceOf(UserScopedMcpClientWrapper.class, wrapper);
        assertEquals("scoped-mcp", wrapper.getName());
        assertFalse(wrapper.isInitialized()); // 构建不连接
    }

    @Test
    void shouldNotWrapForStdioEvenWithUserHeaders() throws Exception {
        writeConfigYaml("stdio-mcp", """
            connection:
              type: stdio
              command: python
              args: ["mcp_server.py"]
            userHeaders:
              headers:
                X-User-Id: userId
            """);

        var wrapper = registrar.buildClient(mcp("stdio-mcp", "stdio-mcp"));
        assertNotNull(wrapper);
        assertFalse(wrapper instanceof UserScopedMcpClientWrapper, "stdio 无 HTTP header,不装饰");
    }

    @Test
    void shouldNotWrapWhenUserHeadersAbsent() throws Exception {
        writeConfigYaml("plain-mcp", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var wrapper = registrar.buildClient(mcp("plain-mcp", "plain-mcp"));
        assertNotNull(wrapper);
        assertFalse(wrapper instanceof UserScopedMcpClientWrapper);
    }

    @Test
    void middlewareKeyConstantAligned() {
        // config.yaml 映射目标 key 与 middleware 写入 key 必须一致（文档契约）
        assertEquals("userId", McpUserContextMiddleware.KEY_USER_ID);
    }
}
