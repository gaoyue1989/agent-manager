package io.agentmanager.framework.browser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import io.agentmanager.framework.config.BrowserProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.sandbox.opensandbox.OpenSandboxEndpoints;
import io.agentmanager.framework.sandbox.opensandbox.SandboxLocator;
import io.agentscope.harness.agent.IsolationScope;

public class BrowserViewWebSocketHandler extends AbstractWebSocketHandler implements SubProtocolCapable {

    private static final Logger log = LoggerFactory.getLogger(BrowserViewWebSocketHandler.class);

    static final String SUBPROTOCOL_BINARY = "binary";
    static final int CLOSE_SANDBOX_NOT_FOUND = 4404;
    static final int CLOSE_UPSTREAM_FAILED = 1011;
    static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    private final BrowserProperties props;
    private final SandboxConfig sandboxConfig;
    private final SandboxLocator sandboxLocator;
    private final VncUpstreamConnector connector;

    private final Map<String, UpstreamPipe> pipes = new ConcurrentHashMap<>();

    public BrowserViewWebSocketHandler(BrowserProperties props, SandboxConfig sandboxConfig,
                                       SandboxLocator sandboxLocator, VncUpstreamConnector connector) {
        this.props = props;
        this.sandboxConfig = sandboxConfig;
        this.sandboxLocator = sandboxLocator;
        this.connector = connector;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(SUBPROTOCOL_BINARY);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
        session.setBinaryMessageSizeLimit(MAX_MESSAGE_BYTES);
        String sessionId = (String) session.getAttributes().get(BrowserViewHandshakeInterceptor.ATTR_SESSION_ID);
        String userId = (String) session.getAttributes().get(BrowserViewHandshakeInterceptor.ATTR_USER_ID);
        String endpoint;
        try (var located = sandboxLocator.locateOwned(userId, sessionId, IsolationScope.USER)) {
            endpoint = OpenSandboxEndpoints.correct(
                located.sandbox().getEndpoint(props.viewPort()).getEndpoint(),
                sandboxConfig.opensandbox() != null ? sandboxConfig.opensandbox().serverUrl() : null);
        } catch (Exception e) {
            log.warn("[browser-view] sandbox not reachable (sid={}): {}", sessionId, e.getMessage());
            closeQuietly(session, CLOSE_SANDBOX_NOT_FOUND, "sandbox_not_found");
            return;
        }
        var uri = BrowserViewEndpoints.upstream(endpoint, props.viewPath());
        String apiKey = sandboxConfig.opensandbox() != null ? sandboxConfig.opensandbox().apiKey() : null;
        var pipe = new UpstreamPipe(session);
        pipes.put(session.getId(), pipe);
        if (!session.isOpen()) {
            pipes.remove(session.getId(), pipe);
            pipe.closeUpstream();
            return;
        }
        try {
            var upstream = connector.connect(uri, apiKey, session.getAcceptedProtocol(), pipe);
            if (!pipe.attach(upstream) || !session.isOpen() || pipes.get(session.getId()) != pipe) {
                pipes.remove(session.getId(), pipe);
                pipe.closeUpstream();
                try {
                    upstream.sendClose(WebSocket.NORMAL_CLOSURE, "client_closed");
                } catch (Exception ignored) {
                }
                upstream.abort();
                return;
            }
            log.info("[browser-view] stream opened (sid={}, upstream={})", sessionId, uri);
        } catch (Exception e) {
            pipes.remove(session.getId(), pipe);
            pipe.closeUpstream();
            log.warn("[browser-view] upstream connect failed (sid={}, uri={}): {}",
                sessionId, uri, e.getMessage());
            closeQuietly(session, CLOSE_UPSTREAM_FAILED, "vnc_upstream_failed");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var pipe = pipes.get(session.getId());
        if (pipe != null) {
            pipe.toUpstreamBinary(message.getPayload());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var pipe = pipes.get(session.getId());
        if (pipe != null) {
            pipe.toUpstreamText(message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var pipe = pipes.remove(session.getId());
        if (pipe != null) {
            pipe.closeUpstream();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        var pipe = pipes.remove(session.getId());
        if (pipe != null) {
            pipe.closeUpstream();
        }
        closeQuietly(session, CLOSE_UPSTREAM_FAILED, "transport_error");
    }

    private static void closeQuietly(WebSocketSession session, int code, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(code, reason));
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    private final class UpstreamPipe implements WebSocket.Listener {

        private final WebSocketSession downstream;
        private final AtomicReference<CompletableFuture<WebSocket>> sendTail =
            new AtomicReference<>(CompletableFuture.completedFuture(null));
        private final java.io.ByteArrayOutputStream binaryFragment = new java.io.ByteArrayOutputStream();
        private final StringBuilder textFragment = new StringBuilder();
        private volatile WebSocket upstream;
        private volatile boolean closed;

        UpstreamPipe(WebSocketSession downstream) {
            this.downstream = downstream;
        }

        boolean attach(WebSocket upstream) {
            synchronized (UpstreamPipe.this) {
                if (closed) {
                    return false;
                }
                this.upstream = upstream;
                return true;
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            synchronized (binaryFragment) {
                binaryFragment.write(chunk, 0, chunk.length);
                if (last) {
                    byte[] payload = binaryFragment.toByteArray();
                    binaryFragment.reset();
                    sendDownstream(new BinaryMessage(payload));
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            synchronized (textFragment) {
                textFragment.append(data);
                if (last) {
                    String payload = textFragment.toString();
                    textFragment.setLength(0);
                    sendDownstream(new TextMessage(payload));
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized (UpstreamPipe.this) {
                if (closed) {
                    return null;
                }
                closed = true;
            }
            int code = (statusCode >= 1000 && statusCode <= 4999) ? statusCode : 1000;
            closeQuietly(downstream, code, reason == null ? "" : reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.info("[browser-view] upstream error (sid={}): {}", downstream.getId(), error.getMessage());
            synchronized (UpstreamPipe.this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            closeQuietly(downstream, CLOSE_UPSTREAM_FAILED, "vnc_upstream_error");
        }

        void toUpstreamBinary(ByteBuffer payload) {
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            chainSend(ws -> ws.sendBinary(ByteBuffer.wrap(bytes), true));
        }

        void toUpstreamText(String payload) {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            chainSend(ws -> ws.sendText(new String(bytes, StandardCharsets.UTF_8), true));
        }

        private void chainSend(java.util.function.Function<WebSocket, CompletableFuture<WebSocket>> send) {
            var ws = upstream;
            if (ws == null) {
                return;
            }
            sendTail.updateAndGet(prev -> prev
                .thenCompose(ignored -> send.apply(ws))
                .exceptionally(error -> {
                    log.debug("[browser-view] upstream send failed: {}", error.getMessage());
                    return ws;
                }));
        }

        private void sendDownstream(org.springframework.web.socket.WebSocketMessage<?> message) {
            try {
                if (downstream.isOpen()) {
                    synchronized (downstream) {
                        downstream.sendMessage(message);
                    }
                }
            } catch (Exception e) {
                log.warn("[browser-view] downstream send failed (sid={}): {}",
                    downstream.getId(), e.getMessage());
            }
        }

        void closeUpstream() {
            WebSocket ws;
            synchronized (UpstreamPipe.this) {
                if (closed) {
                    return;
                }
                closed = true;
                ws = this.upstream;
                this.upstream = null;
            }
            if (ws == null) {
                return;
            }
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client_closed");
            } catch (Exception ignored) {
            }
            ws.abort();
        }
    }
}
