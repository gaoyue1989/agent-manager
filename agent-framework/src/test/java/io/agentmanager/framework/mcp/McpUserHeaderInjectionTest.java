package io.agentmanager.framework.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 HTTP 链路集成测试（T8）：本地 mock MCP server（streamableHttp）断言
 * <ul>
 *   <li>业务工具调用携带 per-call 用户 header，且覆盖静态 Authorization（不重复发送）；</li>
 *   <li>_meta 通道携带调用方 McpMeta entries（McpMeta → CallToolRequest._meta → params._meta）；</li>
 *   <li>连接初始化 / tools/list 仅携带静态凭据，不注入用户 header；</li>
 *   <li>fail-closed：缺值时不发出工具调用请求。</li>
 * </ul>
 */
class McpUserHeaderInjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final List<McpClientWrapper> clients = new ArrayList<>();

    private record Recorded(String httpMethod, JsonNode body, Map<String, List<String>> headers) {
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        clients.forEach(c -> {
            try {
                c.close();
            } catch (Exception ignore) {
                // 尽力而为
            }
        });
        server.stop(0);
    }

    // ===== mock MCP server =====

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var json = raw.isEmpty() ? null : MAPPER.readTree(raw);
            var headers = new HashMap<String, List<String>>();
            exchange.getRequestHeaders().forEach(headers::put);
            requests.add(new Recorded(exchange.getRequestMethod(), json, headers));
            if (json == null) {
                respond(exchange, 202, null);
                return;
            }
            var method = json.path("method").asText("");
            if (method.startsWith("notifications/")) {
                respond(exchange, 202, null);
                return;
            }
            ObjectNode result = switch (method) {
                case "initialize" -> initializeResult(json.path("params"));
                case "tools/list" -> toolsListResult();
                case "tools/call" -> toolsCallResult();
                default -> MAPPER.createObjectNode();
            };
            var response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", json.get("id"));
            response.set("result", result);
            respond(exchange, 200, MAPPER.writeValueAsBytes(response));
        } catch (Exception e) {
            respond(exchange, 500, null);
        }
    }

    private ObjectNode initializeResult(JsonNode params) {
        var result = MAPPER.createObjectNode();
        result.put("protocolVersion", params.path("protocolVersion").asText("2025-06-18"));
        result.putObject("capabilities").putObject("tools");
        var info = result.putObject("serverInfo");
        info.put("name", "mock-mcp");
        info.put("version", "1.0.0");
        return result;
    }

    private ObjectNode toolsListResult() {
        var result = MAPPER.createObjectNode();
        var tool = result.putArray("tools").addObject();
        tool.put("name", "echo");
        tool.put("description", "echo tool");
        tool.putObject("inputSchema").put("type", "object").putObject("properties");
        return result;
    }

    private ObjectNode toolsCallResult() {
        var result = MAPPER.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", "ok");
        result.put("isError", false);
        return result;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body != null) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, body == null ? -1 : body.length);
        if (body != null) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    // ===== helpers =====

    private UserScopedMcpClientWrapper scopedClient(Map<String, String> headers, boolean denyOnMissing) {
        McpClientWrapper inner = McpClientBuilder.create("mock-mcp")
            .streamableHttpTransport("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp")
            .header("Authorization", "Bearer static-token")
            .httpRequestCustomizer(McpUserHeaderCustomizer.userHeader())
            .buildAsync().block();
        assertNotNull(inner);
        clients.add(inner);
        return new UserScopedMcpClientWrapper(inner, new UserHeaderRule(headers, denyOnMissing));
    }

    private List<Recorded> callsTo(String method) {
        return requests.stream()
            .filter(r -> r.body() != null && method.equals(r.body().path("method").asText()))
            .toList();
    }

    /** 大小写不敏感的 header 取值 */
    private static List<String> header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(List.of());
    }

    // ===== tests =====

    @Test
    void shouldSendPerCallUserHeadersAndMetaOnToolCall() {
        var scoped = scopedClient(Map.of("X-User-Id", "userId", "Authorization", "user_token"), true);
        scoped.initialize().block();

        var meta = Map.<String, Object>of("userId", "alice", "user_token", "Bearer user-token");
        var result = scoped.callTool("echo", Map.of(), meta).block();
        assertNotNull(result);
        assertFalse(result.isError());

        var calls = callsTo("tools/call");
        assertEquals(1, calls.size(), "应只发出一次工具调用");
        var call = calls.get(0);

        // HTTP header 通道：用户值注入，且覆盖静态 Authorization（单个值，无重复头）
        assertEquals(List.of("alice"), header(call.headers(), "X-User-Id"));
        assertEquals(List.of("Bearer user-token"), header(call.headers(), "Authorization"),
            "用户 header 应替换静态凭据，而不是与静态值同时发送");

        // _meta 通道：调用方 McpMeta entries 全量进入 params._meta
        var metaNode = call.body().path("params").path("_meta");
        assertEquals("alice", metaNode.path("userId").asText());
        assertEquals("Bearer user-token", metaNode.path("user_token").asText());

        // 初始化 / tools/list 仅携带静态凭据
        var initialize = callsTo("initialize");
        assertEquals(1, initialize.size());
        assertEquals(List.of("Bearer static-token"), header(initialize.get(0).headers(), "Authorization"));
        assertTrue(header(initialize.get(0).headers(), "X-User-Id").isEmpty(),
            "初始化不应携带用户 header");
        for (var list : callsTo("tools/list")) {
            assertTrue(header(list.headers(), "X-User-Id").isEmpty(),
                "tools/list 不应携带用户 header");
        }
    }

    @Test
    void shouldNotIssueToolCallWhenDenyAndMetaMissing() {
        var scoped = scopedClient(Map.of("X-User-Id", "userId"), true);
        scoped.initialize().block();
        int before = callsTo("tools/call").size();

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of()))
            .expectError(McpUserHeaderException.class)
            .verify();

        assertEquals(before, callsTo("tools/call").size(), "deny 时不应发出工具调用请求");
    }

    @Test
    void shouldFallBackToStaticCredentialsWhenPassthroughAndMetaMissing() {
        var scoped = scopedClient(Map.of("Authorization", "user_token"), false);
        scoped.initialize().block();

        var result = scoped.callTool("echo", Map.of(), Map.of()).block();
        assertNotNull(result);

        var calls = callsTo("tools/call");
        assertEquals(1, calls.size());
        // passthrough：未注入用户 header，静态凭据生效
        assertEquals(List.of("Bearer static-token"), header(calls.get(0).headers(), "Authorization"));
    }
}
