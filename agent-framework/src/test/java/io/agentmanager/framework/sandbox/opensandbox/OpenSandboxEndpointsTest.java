package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class OpenSandboxEndpointsTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void correctShouldRestoreDroppedPortOnServerProxyEndpoint() {
        assertEquals("192.168.31.155:8090/v1/sandboxes/sb-1/proxy/44772",
            OpenSandboxEndpoints.correct(
                "192.168.31.155/v1/sandboxes/sb-1/proxy/44772", "192.168.31.155:8090"));
    }

    @Test
    void correctShouldStripSchemeFromServerUrl() {
        assertEquals("osb.local:8090/sandboxes/sb-1/proxy/44772",
            OpenSandboxEndpoints.correct("osb.local/sandboxes/sb-1/proxy/44772", "http://osb.local:8090"));
    }

    @Test
    void correctShouldKeepEndpointWithExplicitPort() {
        assertEquals("192.168.31.155:52051/v1/sandboxes/sb-1/proxy/44772",
            OpenSandboxEndpoints.correct(
                "192.168.31.155:52051/v1/sandboxes/sb-1/proxy/44772", "192.168.31.155:8090"));
    }

    @Test
    void correctShouldKeepDirectEndpoint() {
        assertEquals("172.21.0.2:44772",
            OpenSandboxEndpoints.correct("172.21.0.2:44772", "192.168.31.155:8090"));
    }

    @Test
    void correctShouldKeepEndpointWhenServerUrlHasNoPort() {
        assertEquals("osb.local/v1/sandboxes/sb-1/proxy/44772",
            OpenSandboxEndpoints.correct("osb.local/v1/sandboxes/sb-1/proxy/44772", "osb.local"));
    }

    @Test
    void correctShouldKeepEndpointForDifferentHost() {
        assertEquals("public.example.com/osb/v1/sandboxes/sb-1/proxy/44772",
            OpenSandboxEndpoints.correct(
                "public.example.com/osb/v1/sandboxes/sb-1/proxy/44772", "opensandbox-server.opensandbox-system.svc:80"));
    }

    @Test
    void correctShouldKeepEndpointWhenServerUrlMissing() {
        assertEquals("osb.local/v1/sandboxes/sb-1/proxy/44772",
            OpenSandboxEndpoints.correct("osb.local/v1/sandboxes/sb-1/proxy/44772", null));
    }

    @Test
    void withPortFidelityShouldRouteExecdRequestsToConfiguredPort() throws Exception {
        AtomicInteger execdListHits = new AtomicInteger();
        int port = startFakeServer(execdListHits, false);
        var config = ConnectionConfig.builder()
            .domain("127.0.0.1:" + port)
            .apiKey("k")
            .protocol("http")
            .useServerProxy(true)
            .build();
        Sandbox handle = Sandbox.connector()
            .sandboxId("sb-1")
            .connectionConfig(config)
            .skipHealthCheck(true)
            .connect();

        var corrected = OpenSandboxEndpoints.withPortFidelity(handle, "127.0.0.1:" + port).sandbox();

        assertNotSame(handle, corrected);
        assertEquals("sb-1", corrected.getId());
        assertEquals(List.of(), corrected.files().listDirectory("/workspace"));
        assertTrue(execdListHits.get() > 0, "execd 请求必须落到配置端口的 server proxy");
        corrected.close();
    }

    @Test
    void withPortFidelityShouldKeepHandleWhenEndpointAlreadyHasPort() throws Exception {
        AtomicInteger execdListHits = new AtomicInteger();
        int port = startFakeServer(execdListHits, true);
        var config = ConnectionConfig.builder()
            .domain("127.0.0.1:" + port)
            .apiKey("k")
            .protocol("http")
            .useServerProxy(true)
            .build();
        Sandbox handle = Sandbox.connector()
            .sandboxId("sb-1")
            .connectionConfig(config)
            .skipHealthCheck(true)
            .connect();

        assertSame(handle, OpenSandboxEndpoints.withPortFidelity(handle, "127.0.0.1:" + port).sandbox());
        handle.close();
    }

    @Test
    void withPortFidelityShouldTolerateNullEndpoint() {
        Sandbox handle = org.mockito.Mockito.mock(Sandbox.class);
        assertSame(handle, OpenSandboxEndpoints.withPortFidelity(handle, "127.0.0.1:8090").sandbox());
    }

    @Test
    void sdkShouldSurfaceExecdFileNotFoundCodeOn404() throws Exception {
        int port = startFakeServer(new AtomicInteger(), false);
        Sandbox corrected = correctedHandle(port);
        try {
            var e = assertThrows(SandboxApiException.class,
                () -> corrected.files().readByteArray("/missing-json"));
            assertEquals(404, e.getStatusCode());
            assertEquals("FILE_NOT_FOUND", e.getError().getCode());
        } finally {
            corrected.close();
        }
    }

    @Test
    void sdkShouldExposeNginxHtml404WithoutFileNotFoundCode() throws Exception {
        int port = startFakeServer(new AtomicInteger(), false);
        Sandbox corrected = correctedHandle(port);
        try {
            var e = assertThrows(SandboxApiException.class,
                () -> corrected.files().readByteArray("/missing-html"));
            assertEquals(404, e.getStatusCode());
            assertNotEquals("FILE_NOT_FOUND", e.getError() == null ? null : e.getError().getCode());
            assertTrue(e.getResponseBody() != null && e.getResponseBody().contains("<!DOCTYPE html"),
                "HTML 404 body 必须可见，否则无法与路径缺失区分: " + e.getResponseBody());
        } finally {
            corrected.close();
        }
    }

    @Test
    void sdkShouldExposeEmptyBody404ForE2eMockCompatibility() throws Exception {
        int port = startFakeServer(new AtomicInteger(), false);
        Sandbox corrected = correctedHandle(port);
        try {
            var e = assertThrows(SandboxApiException.class,
                () -> corrected.files().readByteArray("/missing-empty"));
            assertEquals(404, e.getStatusCode());
            assertTrue(e.getResponseBody() == null || e.getResponseBody().isBlank(),
                "mock 空 body 404 必须保持空 body，否则 e2e 路径缺失语义回归: " + e.getResponseBody());
        } finally {
            corrected.close();
        }
    }

    private Sandbox correctedHandle(int port) throws Exception {
        Sandbox handle = Sandbox.connector()
            .sandboxId("sb-1")
            .connectionConfig(ConnectionConfig.builder()
                .domain("127.0.0.1:" + port)
                .apiKey("k")
                .protocol("http")
                .useServerProxy(true)
                .build())
            .skipHealthCheck(true)
            .connect();
        return OpenSandboxEndpoints.withPortFidelity(handle, "127.0.0.1:" + port).sandbox();
    }

    private int startFakeServer(AtomicInteger execdListHits, boolean endpointCarriesPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String authority = endpointCarriesPort ? "127.0.0.1:" + port : "127.0.0.1";
        server.createContext("/", exchange -> handle(exchange, execdListHits, authority));
        server.start();
        return port;
    }

    private static void handle(HttpExchange exchange, AtomicInteger execdListHits, String authority)
            throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.contains("/endpoints/")) {
            String endpoint = authority + path.substring(0, path.indexOf("/endpoints/"))
                + "/proxy/" + path.substring(path.lastIndexOf('/') + 1);
            respond(exchange, 200, "{\"endpoint\":\"" + endpoint + "\"}");
            return;
        }
        if (path.endsWith("/directories/list")) {
            execdListHits.incrementAndGet();
            respond(exchange, 200, "[]");
            return;
        }
        if (path.endsWith("/files/download")) {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("missing-json")) {
                respond(exchange, 404, "{\"code\":\"FILE_NOT_FOUND\",\"message\":\"file not found. open /missing-json\"}");
                return;
            }
            if (query != null && query.contains("missing-html")) {
                respond(exchange, 404, "<!DOCTYPE html>\n<html><head><title>404 Not Found</title></head>"
                    + "<body><h1>Not Found</h1><p>Test Page for the HTTP Server on Anolis OS</p></body></html>",
                    "text/html");
                return;
            }
            if (query != null && query.contains("missing-empty")) {
                respond(exchange, 404, "");
                return;
            }
            respond(exchange, 200, "ok");
            return;
        }
        if (path.equals("/v1/metrics/events")) {
            respond(exchange, 204, "");
            return;
        }
        respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body, "application/json");
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
