package io.agentmanager.framework.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.mcp.McpUserContextMiddleware;
import io.agentmanager.framework.mcp.UserHeaderRule;
import io.agentmanager.framework.mcp.UserScopedMcpClientWrapper;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    // ===== reqHeader: 取值源（mcp-chat-header-forward-design §5）=====

    @Test
    void shouldNormalizeReqHeaderValueToLowercase() {
        // 作者写大写也归一为小写，保证与捕获键（小写）确定匹配
        var rule = registrar.parseUserHeaders(
            Map.of("userHeaders", Map.of("headers", Map.of(
                "Authorization", "reqHeader:Authorization",
                "X-Request-Id", "reqheader:x-request-id"))),
            "srv");

        assertNotNull(rule);
        assertEquals("reqHeader:authorization", rule.headers().get("Authorization"));
        // 前缀本身大小写敏感：reqheader: 不是保留命名空间，按普通 meta key 处理
        assertEquals("reqheader:x-request-id", rule.headers().get("X-Request-Id"));
    }

    @Test
    void shouldDropDeniedReqHeaderMapping() {
        // 平台 denylist（cookie / x-forwarded-*）在解析期剔除；全部被剔 → null
        var partial = registrar.parseUserHeaders(
            Map.of("userHeaders", Map.of("headers", Map.of(
                "Authorization", "reqHeader:authorization",
                "Cookie", "reqHeader:cookie",
                "X-Tenant", "reqHeader:x-forwarded-tenant"))),
            "srv");
        assertNotNull(partial);
        assertEquals(Map.of("Authorization", "reqHeader:authorization"), partial.headers());

        assertNull(registrar.parseUserHeaders(
            Map.of("userHeaders", Map.of("headers", Map.of("Cookie", "reqHeader:cookie"))), "srv"));
    }

    @Test
    void forwardHeaderNamesShouldUnionDeclaredReqHeadersAndFollowClearServer() throws Exception {
        // 预置规则缓存（registerOneForTest 模拟 registerOne 装载，读 tempDir 下 config.yaml）
        writeConfigYaml("srv-a", """
            connection:
              type: streamableHttp
              url: https://mcp.example.com/mcp
            userHeaders:
              headers:
                Authorization: reqHeader:authorization
                X-User-Id: userId
            """);
        writeConfigYaml("srv-b", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            userHeaders:
              headers:
                X-Request-Id: reqHeader:x-request-id
                Authorization: reqHeader:AUTHORIZATION
            """);

        registrar.registerOneForTest(new Toolkit(), mcp("srv-a", "srv-a"));
        registrar.registerOneForTest(new Toolkit(), mcp("srv-b", "srv-b"));

        // 并集去重；reqHeader:AUTHORIZATION 归一为小写
        assertEquals(Set.of("authorization", "x-request-id"), registrar.getForwardHeaderNames());

        // clearServer 移除该 server 的规则，名单随之收缩（srv-b 摘除后仅剩 srv-a 的 authorization）
        var toolkit = mock(Toolkit.class);
        when(toolkit.removeMcpClient("srv-b")).thenReturn(reactor.core.publisher.Mono.empty());
        assertTrue(registrar.clearServer(toolkit, "srv-b"));
        assertEquals(Set.of("authorization"), registrar.getForwardHeaderNames());
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
    void shouldWrapStdioForMetaStripOnly() throws Exception {
        // issue #225：stdio 也常驻装饰（EMPTY rule 仅做 _meta 剥离、无 header 注入）——
        // HTTP server 声明的透传凭据随 per-turn 全 server 共享 McpMeta 会漏进 stdio
        // 子进程的 CallToolRequest._meta，剥离必须对全部传输类型生效
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
        assertInstanceOf(UserScopedMcpClientWrapper.class, wrapper, "stdio 常驻装饰，仅剥离不注入");

        // EMPTY rule（stdio 时 buildClient 固定传入）无 header 注入语义：delegate 收到的
        // meta 剥离 reqHeader:*、不写 McpTransportContext（复用 RecordingClient 模式）
        var delegate = new RecordingClient();
        var scoped = new UserScopedMcpClientWrapper(delegate, UserHeaderRule.EMPTY);
        var result = scoped.callTool("echo", Map.of(), Map.of(
                "userId", "alice",
                "reqHeader:authorization", "secret")).block();

        assertNotNull(result);
        assertFalse(result.isError());
        var seen = delegate.seenMeta.get();
        assertNotNull(seen);
        assertEquals("alice", seen.get("userId"), "userId _meta 双通道不变");
        assertTrue(seen.keySet().stream().noneMatch(k -> String.valueOf(k).startsWith("reqHeader:")),
            "reqHeader:* 必须剥离，不随 _meta 下发");
        assertNull(delegate.seenTransportContext.get(), "EMPTY rule 无 header 注入");
    }

    @Test
    void shouldWrapPlainHttpTooForMetaStripResidency() throws Exception {
        // 常驻装饰（mcp-chat-header-forward-design §6.4）：未配置 userHeaders 的 HTTP server
        // 也装饰——剥离是全 server 的 _meta 卫生行为；空 rule 时行为与旧版纯透传一致
        writeConfigYaml("plain-mcp", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var wrapper = registrar.buildClient(mcp("plain-mcp", "plain-mcp"));
        assertNotNull(wrapper);
        assertInstanceOf(UserScopedMcpClientWrapper.class, wrapper);
    }

    @Test
    void middlewareKeyConstantAligned() {
        // config.yaml 映射目标 key 与 middleware 写入 key 必须一致（文档契约）
        assertEquals("userId", McpUserContextMiddleware.KEY_USER_ID);
    }

    /** 记录 delegate 收到的 meta 与 McpTransportContext（复用 UserScopedMcpClientWrapperTest 模式） */
    private static class RecordingClient extends McpClientWrapper {
        static final McpSchema.CallToolResult OK = McpSchema.CallToolResult.builder()
            .content(List.of(new McpSchema.TextContent("ok")))
            .isError(false)
            .build();
        final AtomicReference<Map<String, Object>> seenMeta = new AtomicReference<>();
        final AtomicReference<McpTransportContext> seenTransportContext = new AtomicReference<>();

        RecordingClient() {
            super("test-server");
        }

        @Override
        public Mono<Void> initialize() {
            return Mono.empty();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return Mono.just(List.of());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
            return callTool(toolName, arguments, null);
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
            seenMeta.set(meta);
            return Mono.deferContextual(cv -> {
                seenTransportContext.set(cv.getOrDefault(McpTransportContext.KEY, null));
                return Mono.just(OK);
            });
        }

        @Override
        public void close() {
        }
    }
}
