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
import java.util.concurrent.TimeUnit;
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
    private final AtomicInteger createSessionCalls = new AtomicInteger();
    private final AtomicInteger appendMessageCalls = new AtomicInteger();
    private volatile boolean failCreateSession = false;
    private volatile boolean return404OnAppend = false;
    private volatile boolean hangRegister = false;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            var t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/api/v1/search/search", this::handleSearch);
        server.createContext("/api/v1/admin/accounts", this::handleRegister);
        server.createContext("/api/v1/sessions", this::handleSessions);
        server.createContext("/mcp", ex -> {
            send(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":"
                + "[{\"type\":\"text\",\"text\":\"Submitted 2 message(s)\"}]}}");
        });
        server.start();
    }

    private void handleSessions(HttpExchange ex) throws IOException {
        var path = ex.getRequestURI().getPath();
        if (path.equals("/api/v1/sessions")) {
            createSessionCalls.incrementAndGet();
            if (failCreateSession) {
                send(ex, 500, "{\"status\":\"error\",\"error\":{\"code\":\"INTERNAL\"}}");
                return;
            }
            send(ex, 200, "{\"status\":\"ok\",\"result\":{\"session_id\":\"ov-s-1\"}}");
            return;
        }
        if (path.startsWith("/api/v1/sessions/") && path.endsWith("/messages")) {
            appendMessageCalls.incrementAndGet();
            if (return404OnAppend) {
                send(ex, 404, "{\"status\":\"error\"}");
                return;
            }
            send(ex, 200, "{\"status\":\"ok\"}");
            return;
        }
        send(ex, 404, "{}");
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
            if (hangRegister) {
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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

    @Test
    void appendSessionCreatesOvSessionOnceThenReuses() {
        unregisteredUser = false;
        var client = new VikingMemoryClient(config());
        client.appendSessionMessage("u-1001", "slot:sa", "user", "第一问");
        client.appendSessionMessage("u-1001", "slot:sa", "assistant", "第一答");
        assertEquals(1, createSessionCalls.get(), "同一会话必须复用同一个 OV session");
        assertEquals(2, appendMessageCalls.get());
    }

    @Test
    void append404ShouldEvictCachedOvSessionIdAndRecreateOnNextAppend() {
        unregisteredUser = false;
        var client = new VikingMemoryClient(config());
        client.appendSessionMessage("u-1001", "slot:s404", "user", "first");
        assertEquals(1, createSessionCalls.get());
        assertTrue(client.ovSessionIds.containsKey("slot:s404"));

        return404OnAppend = true;
        client.appendSessionMessage("u-1001", "slot:s404", "user", "expired");
        assertFalse(client.ovSessionIds.containsKey("slot:s404"));

        return404OnAppend = false;
        client.appendSessionMessage("u-1001", "slot:s404", "user", "recreated");
        assertEquals(2, createSessionCalls.get());
        assertTrue(client.ovSessionIds.containsKey("slot:s404"));
    }

    @Test
    void createSessionFailureStopsRetryStormWithinCooldown() {
        unregisteredUser = false;
        failCreateSession = true;
        var client = new VikingMemoryClient(config());
        for (int i = 0; i < 5; i++) {
            client.appendSessionMessage("u-1001", "slot:sb", "user", "内容" + i);
        }
        assertEquals(1, createSessionCalls.get(),
            "createSession 失败必须负缓存，冷却期内不得重复外呼");
        assertEquals(0, appendMessageCalls.get());
    }

    /**
     * issue #496：ensureUser 必须有请求读超时——search 快速 401 后开户端点挂死时，
     * searchContext 必须在 searchTimeoutMs + 开户超时上限内有界降级返回 null，
     * 不得无限期挂死首推理线程与单线程 commitWorker。
     */
    @Test
    void ensureUserHangMustDegradeWithinRequestTimeoutBound() {
        hangRegister = true;
        var client = new VikingMemoryClient(config());
        long start = System.nanoTime();
        var text = client.searchContext("u-1001", "用户的回复偏好");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertNull(text);
        assertTrue(elapsedMs < 4500,
            "ensureUser 挂死时 searchContext 必须有界降级（开户超时上限），took " + elapsedMs + "ms");
    }

    /** issue #496：OV session 映射缓存有界（LRU 淘汰，丢失仅导致重建 session，语义无损） */
    @Test
    void ovSessionCacheMustStayBounded() throws Exception {
        unregisteredUser = false;
        var client = new VikingMemoryClient(config());
        concurrentlyAppend(client, 1200, "slot:ov-");
        assertTrue(client.ovSessionIds.size() <= VikingMemoryClient.SESSION_CACHE_CAPACITY,
            "ovSessionIds 无界增长：实际 " + client.ovSessionIds.size());
    }

    /** issue #496：createSession 失败负缓存同样有界 */
    @Test
    void failedSessionCacheMustStayBounded() throws Exception {
        unregisteredUser = false;
        failCreateSession = true;
        var client = new VikingMemoryClient(config());
        concurrentlyAppend(client, 1200, "slot:fail-");
        assertTrue(client.failedSessionAt.size() <= VikingMemoryClient.SESSION_CACHE_CAPACITY,
            "failedSessionAt 无界增长：实际 " + client.failedSessionAt.size());
    }

    private static void concurrentlyAppend(VikingMemoryClient client, int total, String slotPrefix)
            throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> client.appendSessionMessage("u-1001", slotPrefix + idx, "user", "内容" + idx));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS),
            "并发 append 必须在时限内全部完成（fail-soft，不得挂死）");
    }
}
