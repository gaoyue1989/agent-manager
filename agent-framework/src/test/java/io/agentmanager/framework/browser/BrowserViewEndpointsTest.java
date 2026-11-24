package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BrowserViewEndpointsTest {

    private static final String ENDPOINT =
        "opensandbox-server.opensandbox-system.svc:80/v1/sandboxes/sb-1/proxy/6080";

    @Test
    void schemeLessEndpointShouldBecomeWebSocket() {
        assertEquals("ws://" + ENDPOINT + "/websockify",
            BrowserViewEndpoints.upstream(ENDPOINT, "/websockify").toString());
    }

    @Test
    void httpEndpointShouldMapToWs() {
        assertEquals("ws://host:18600/v1/sandboxes/sb-1/proxy/6080/websockify",
            BrowserViewEndpoints.upstream("http://host:18600/v1/sandboxes/sb-1/proxy/6080", "/websockify")
                .toString());
    }

    @Test
    void httpsEndpointShouldMapToWss() {
        assertEquals("wss://host/v1/sandboxes/sb-1/proxy/6080/websockify",
            BrowserViewEndpoints.upstream("https://host/v1/sandboxes/sb-1/proxy/6080", "websockify")
                .toString());
    }

    @Test
    void trailingSlashAndBlankPathShouldBeNormalized() {
        assertEquals("ws://host:8080/proxy/6080",
            BrowserViewEndpoints.upstream("host:8080/proxy/6080/", "").toString());
        assertEquals("ws://host:8080/proxy/6080/websockify",
            BrowserViewEndpoints.upstream("host:8080/proxy/6080", " websockify ").toString());
    }
}
