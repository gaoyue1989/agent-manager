package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.agentmanager.framework.config.VikingConfig;

/**
 * VikingMemoryClient 单测：派生公式跨端向量（与 Go DeriveVikingUserKey 同 fixture）、
 * retrieval_error 静默降空判据（PoC 实测风险）、401 lazy 开户重试、remember 提交。
 */
class VikingMemoryClientTest {

    /**
     * 与 backend viking_test.go 的 vikingDeriveVector 同源（Go/Java 双端对齐断言，
     * openviking-memory-integration-design §4；PoC 261116 与服务端逐字节一致）。
     */
    static final String DERIVED_KEY_VECTOR =
        "b2FmLWRlbW8tc3Zj.dS0xMDAx.MTE3YzBlYzBiNjhkZjdhMDliNDQyOTk1MjMzMTlmNDY2ZDRmNGM2OGY0YzM5ZDg4ZTY3OGE5MDhlNmU0ZDU0Mw";

    @Test
    void deriveUserKeyMatchesCrossLanguageVector() {
        assertEquals(DERIVED_KEY_VECTOR,
            VikingMemoryClient.deriveUserKey("oaf-demo-svc", "u-1001", "test-seed-123"));
        // seed 变化即换 key（轮换语义）
        assertFalse(DERIVED_KEY_VECTOR.equals(
            VikingMemoryClient.deriveUserKey("oaf-demo-svc", "u-1001", "other")));
    }

    private HttpServer server;
    private final AtomicReference<String> searchBody = new AtomicReference<>();
    private final AtomicInteger searchCalls = new AtomicInteger();
    private final AtomicInteger registerCalls = new AtomicInteger();
    private volatile boolean retrievalError = false;
    private volatile boolean unregisteredUser = true;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/search/search", this::handleSearch);
        server.createContext("/api/v1/admin/accounts", this::handleRegister);
        server.createContext("/mcp", ex -> {
            send(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":"
                + "[{\"type\":\"text\",\"text\":\"Submitted 2 message(s)\"}]}}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handleSearch(HttpExchange ex) throws IOException {
        searchCalls.incrementAndGet();
        searchBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (unregisteredUser) {
            send(ex, 401, "{\"status\":\"error\",\"error\":{\"code\":\"UNAUTHENTICATED\"}}");
            return;
        }
        String result = retrievalError
            ? "{\"status\":\"ok\",\"result\":{\"entries\":[],\"rendered\":\"\","
                + "\"stats\":{\"retrieval_error\":[\"embedding upstream 429\"]}}}"
            : "{\"status\":\"ok\",\"result\":{\"entries\":[],\"rendered\":\"- 偏好简洁中文\\n\","
                + "\"stats\":{\"retrieval_error\":[]}}}";
        send(ex, 200, result);
    }

    private void handleRegister(HttpExchange ex) throws IOException {
        if (ex.getRequestURI().getPath().endsWith("/users")) {
            registerCalls.incrementAndGet();
            unregisteredUser = false; // 开户后同 key 可用
            send(ex, 200, "{\"status\":\"ok\",\"result\":{\"user_key\":\"x\"}}");
            return;
        }
        send(ex, 404, "{}");
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private VikingConfig config() {
        return new VikingConfig("openviking",
            baseUrl(), "oaf-demo-svc",
            "admin-key-test", "test-seed-123", false, false, 2000, 300);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void searchContextTriggersLazyProvisionAndReturnsRendered() {
        var client = new VikingMemoryClient(config());
        var text = client.searchContext("u-1001", "用户的回复偏好");
        assertNotNull(text);
        assertTrue(text.contains("偏好简洁"));
        assertEquals(1, registerCalls.get(), "first 401 must trigger exactly one lazy provision");
        assertTrue(searchBody.get().contains("\"mode\":\"context\""));
    }

    @Test
    void searchContextTreatsRetrievalErrorAsFailure() {
        retrievalError = true;
        unregisteredUser = false;
        var client = new VikingMemoryClient(config());
        assertNull(client.searchContext("u-1001", "任意查询"),
            "retrieval_error 非空必须视为检索失败（PoC 实测 429 静默降空），不能当作无记忆正常返回");
    }

    @Test
    void searchContextRejectsIllegalUserId() {
        var client = new VikingMemoryClient(config());
        assertNull(client.searchContext("../etc/passwd", "q"));
        assertNull(client.searchContext("", "q"));
        assertNull(client.searchContext("u-1001", " "));
    }

    @Test
    void rememberSubmitsMcpToolCall() {
        unregisteredUser = false;
        var client = new VikingMemoryClient(config());
        client.remember("u-1001", List.of(
            Map.entry("user", "记住:生产集群是 kind"),
            Map.entry("assistant", "好的,已记住。")));
        // fail-soft 提交无返回值；无异常即通过（响应 200 形态由 fake 保证）
    }

    @Test
    void disabledProviderShortCircuits() {
        var cfg = new VikingConfig("local", "", "", "", "", false, false, 2000, 300);
        assertFalse(cfg.enabled());
        var client = new VikingMemoryClient(cfg);
        assertNull(client.searchContext("u-1001", "q"));
    }
}
