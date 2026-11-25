package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint;

import io.agentmanager.framework.config.BrowserProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.sandbox.opensandbox.LocatedSandbox;
import io.agentmanager.framework.sandbox.opensandbox.SandboxEnvException;
import io.agentmanager.framework.sandbox.opensandbox.SandboxLocator;
import io.agentscope.harness.agent.IsolationScope;

class BrowserViewWebSocketHandlerTest {

    private static final String SESSION = "sess-1";
    private static final String USER = "u-1";
    private static final String ENDPOINT = "opensandbox-server.svc:80/v1/sandboxes/sb-1/proxy/6080";

    private SandboxLocator locator;
    private VncUpstreamConnector connector;
    private WebSocketSession session;
    private BrowserViewWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        locator = mock(SandboxLocator.class);
        connector = mock(VncUpstreamConnector.class);
        session = mock(WebSocketSession.class);
        var attrs = new HashMap<String, Object>(Map.of(
            BrowserViewHandshakeInterceptor.ATTR_SESSION_ID, SESSION,
            BrowserViewHandshakeInterceptor.ATTR_USER_ID, USER));
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("ws-1");
        when(session.isOpen()).thenReturn(true);
        handler = new BrowserViewWebSocketHandler(
            new BrowserProperties(true, 60, 6080, "/websockify"),
            new SandboxConfig(false, "img", 60, 30, 1024, 1, List.of("/e"),
                java.time.Duration.ofMillis(100), true, true, 900, true, true, true, false, 500, 30,
                new SandboxConfig.OpenSandboxConfig("server:8090", "api-key")),
            locator, connector);
    }

    private void locateEndpoint(String endpoint) {
        var sandbox = mock(Sandbox.class);
        var sandboxEndpoint = mock(SandboxEndpoint.class);
        when(sandboxEndpoint.getEndpoint()).thenReturn(endpoint);
        when(sandbox.getEndpoint(anyInt())).thenReturn(sandboxEndpoint);
        when(locator.locateOwned(USER, SESSION, IsolationScope.USER))
            .thenReturn(new LocatedSandbox("sb-1", sandbox));
    }

    @Test
    void missingSandboxShouldCloseWithNotFound() throws Exception {
        when(locator.locateOwned(anyString(), anyString(), any()))
            .thenThrow(new SandboxEnvException("sandbox_not_found", 404, "nope"));

        handler.afterConnectionEstablished(session);

        var captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertEquals(BrowserViewWebSocketHandler.CLOSE_SANDBOX_NOT_FOUND, captor.getValue().getCode());
    }

    @Test
    void handshakeShouldForwardEndpointApiKeyAndSubprotocol() throws Exception {
        locateEndpoint(ENDPOINT);
        when(session.getAcceptedProtocol()).thenReturn("binary");
        var upstream = mock(WebSocket.class);
        when(connector.connect(any(), anyString(), anyString(), any())).thenReturn(upstream);

        handler.afterConnectionEstablished(session);

        var uri = ArgumentCaptor.forClass(URI.class);
        var key = ArgumentCaptor.forClass(String.class);
        var subprotocol = ArgumentCaptor.forClass(String.class);
        verify(connector).connect(uri.capture(), key.capture(), subprotocol.capture(), any());
        assertEquals("ws://" + ENDPOINT + "/websockify", uri.getValue().toString());
        assertEquals("api-key", key.getValue());
        assertEquals("binary", subprotocol.getValue());
    }

    @Test
    void handshakeShouldRestorePortDroppedByFrontingProxy() throws Exception {
        locateEndpoint("server/v1/sandboxes/sb-1/proxy/6080");
        when(session.getAcceptedProtocol()).thenReturn("binary");
        var upstream = mock(WebSocket.class);
        when(connector.connect(any(), anyString(), anyString(), any())).thenReturn(upstream);

        handler.afterConnectionEstablished(session);

        var uri = ArgumentCaptor.forClass(URI.class);
        verify(connector).connect(uri.capture(), anyString(), anyString(), any());
        assertEquals("ws://server:8090/v1/sandboxes/sb-1/proxy/6080/websockify",
            uri.getValue().toString(), "endpoint 丢端口须按配置的 server 地址校正");
    }

    @Test
    void upstreamConnectFailureShouldCloseWithUpstreamFailed() throws Exception {
        locateEndpoint(ENDPOINT);
        when(connector.connect(any(), any(), any(), any()))
            .thenThrow(new java.io.IOException("refused"));
        handler.afterConnectionEstablished(session);

        var captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertEquals(BrowserViewWebSocketHandler.CLOSE_UPSTREAM_FAILED, captor.getValue().getCode());
    }

    @Test
    void downstreamBinaryShouldReachUpstream() throws Exception {
        var upstream = mock(WebSocket.class);
        when(upstream.sendBinary(any(), eq(true))).thenReturn(CompletableFuture.completedFuture(upstream));
        var listener = new AtomicReference<WebSocket.Listener>();
        setupPipe(upstream, listener);

        var payload = ByteBuffer.wrap("RFB".getBytes(StandardCharsets.UTF_8));
        handler.handleMessage(session, new BinaryMessage(payload));

        verify(upstream).sendBinary(eq(ByteBuffer.wrap("RFB".getBytes(StandardCharsets.UTF_8))), eq(true));
    }

    @Test
    void upstreamFragmentsShouldBeAssembledAndForwarded() throws Exception {
        var upstream = mock(WebSocket.class);
        var listener = new AtomicReference<WebSocket.Listener>();
        setupPipe(upstream, listener);

        var l = listener.get();
        l.onOpen(upstream);
        l.onBinary(upstream, ByteBuffer.wrap("RFB ".getBytes(StandardCharsets.UTF_8)), false);
        l.onBinary(upstream, ByteBuffer.wrap("003.008".getBytes(StandardCharsets.UTF_8)), true);

        var captor = ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(captor.capture());
        var message = (BinaryMessage) captor.getValue();
        assertEquals("RFB 003.008", new String(message.getPayload().array(), StandardCharsets.UTF_8));
    }

    @Test
    void upstreamTextShouldPassThrough() throws Exception {
        var upstream = mock(WebSocket.class);
        var listener = new AtomicReference<WebSocket.Listener>();
        setupPipe(upstream, listener);

        listener.get().onText(upstream, "aGVsbG8=", true);

        var captor = ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(captor.capture());
        assertTrue(captor.getValue() instanceof TextMessage);
        assertEquals("aGVsbG8=", ((TextMessage) captor.getValue()).getPayload());
    }

    @Test
    void upstreamCloseShouldCloseDownstream() throws Exception {
        var upstream = mock(WebSocket.class);
        var listener = new AtomicReference<WebSocket.Listener>();
        setupPipe(upstream, listener);

        listener.get().onClose(upstream, 4001, "app close");

        var captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertEquals(4001, captor.getValue().getCode());
    }

    private void setupPipe(WebSocket upstream, AtomicReference<WebSocket.Listener> listener) throws Exception {
        locateEndpoint(ENDPOINT);
        when(connector.connect(any(), any(), any(), any())).thenAnswer(inv -> {
            listener.set(inv.getArgument(3));
            return upstream;
        });
        handler.afterConnectionEstablished(session);
        assertTrue(listener.get() != null, "upstream listener must be installed");
    }
}
