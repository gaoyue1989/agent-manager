package io.agentmanager.framework.browser;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import io.agentmanager.framework.service.SessionAccessGuard;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.util.PathSafe;

public class BrowserViewHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BrowserViewHandshakeInterceptor.class);

    public static final String ATTR_SESSION_ID = "browserViewSessionId";
    public static final String ATTR_USER_ID = "browserViewUserId";

    private static final Pattern VIEW_PATH = Pattern.compile(".*/threads/([^/]+)/browser/vnc/?$");

    private final SessionUserStore sessionUserStore;
    private final BrowserViewTicketStore ticketStore;
    private final OriginHandshakeInterceptor originInterceptor;

    public BrowserViewHandshakeInterceptor(SessionUserStore sessionUserStore,
                                           BrowserViewTicketStore ticketStore) {
        this(sessionUserStore, ticketStore, List.of());
    }

    public BrowserViewHandshakeInterceptor(SessionUserStore sessionUserStore,
                                           BrowserViewTicketStore ticketStore,
                                           List<String> allowedOriginPatterns) {
        this.sessionUserStore = sessionUserStore;
        this.ticketStore = ticketStore;
        this.originInterceptor = new OriginHandshakeInterceptor();
        if (allowedOriginPatterns != null && !allowedOriginPatterns.isEmpty()) {
            this.originInterceptor.setAllowedOriginPatterns(allowedOriginPatterns);
        }
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        var path = request.getURI().getPath();
        var matcher = VIEW_PATH.matcher(path);
        if (!matcher.matches()) {
            log.warn("[browser-view] handshake rejected: unexpected path {}", path);
            return false;
        }
        try {
            if (!originInterceptor.beforeHandshake(withForwardedOrigin(request), response, wsHandler, attributes)) {
                log.info("[browser-view] handshake rejected: origin not allowed (path={})", path);
                return false;
            }
        } catch (Exception e) {
            log.warn("[browser-view] handshake rejected: origin check error ({})", e.getMessage());
            return false;
        }
        String sessionId = PathSafe.sanitize(matcher.group(1));
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build()
            .getQueryParams().getFirst("ticket");
        if (ticket == null || ticket.isBlank()) {
            log.info("[browser-view] handshake rejected: missing view ticket (sid={})", sessionId);
            return false;
        }
        var claim = ticketStore.consume(ticket);
        if (claim == null || !sessionId.equals(claim.sessionId())) {
            log.info("[browser-view] handshake rejected: invalid view ticket (sid={})", sessionId);
            return false;
        }
        String headerUserId = request.getHeaders().getFirst("X-User-Id");
        if (headerUserId != null && !headerUserId.isBlank()
                && !SessionAccessGuard.normalizeRequester(headerUserId).equals(claim.userId())) {
            log.warn("[browser-view] handshake rejected: header identity conflicts with view ticket (sid={})",
                sessionId);
            return false;
        }
        var owner = SessionAccessGuard.establishedOwner(sessionUserStore, sessionId, claim.userId());
        if (owner == null) {
            log.info("[browser-view] handshake rejected: session ownership not established (sid={})", sessionId);
            return false;
        }
        attributes.put(ATTR_SESSION_ID, sessionId);
        attributes.put(ATTR_USER_ID, owner);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    static ServerHttpRequest withForwardedOrigin(ServerHttpRequest request) {
        var headers = request.getHeaders();
        String fwdProto = firstToken(headers.getFirst("X-Forwarded-Proto"));
        String fwdHost = firstToken(headers.getFirst("X-Forwarded-Host"));
        String fwdPort = firstToken(headers.getFirst("X-Forwarded-Port"));
        if (fwdProto == null && fwdHost == null && fwdPort == null) {
            return request;
        }
        var base = request.getURI();
        String scheme = fwdProto != null ? fwdProto : base.getScheme();
        String host = base.getHost();
        int port = base.getPort();
        if (fwdHost != null) {
            int colon = fwdHost.lastIndexOf(':');
            if (colon > 0 && fwdHost.indexOf(']') < colon) {
                host = fwdHost.substring(0, colon);
                try {
                    port = Integer.parseInt(fwdHost.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    port = -1;
                }
            } else {
                host = fwdHost;
                port = -1;
            }
        }
        if (port < 0 && fwdPort != null) {
            try {
                port = Integer.parseInt(fwdPort);
            } catch (NumberFormatException ignored) {
            }
        }
        var resolved = UriComponentsBuilder.fromUri(base)
            .scheme(scheme)
            .host(host)
            .port(port > 0 ? port : -1)
            .build(true)
            .toUri();
        return new ServerHttpRequest() {
            @Override
            public java.net.URI getURI() {
                return resolved;
            }

            @Override
            public org.springframework.http.HttpMethod getMethod() {
                return request.getMethod();
            }

            @Override
            public org.springframework.http.HttpHeaders getHeaders() {
                return request.getHeaders();
            }

            @Override
            public java.io.InputStream getBody() throws java.io.IOException {
                return request.getBody();
            }

            @Override
            public java.security.Principal getPrincipal() {
                return request.getPrincipal();
            }

            @Override
            public java.net.InetSocketAddress getLocalAddress() {
                return request.getLocalAddress();
            }

            @Override
            public java.net.InetSocketAddress getRemoteAddress() {
                return request.getRemoteAddress();
            }

            @Override
            public org.springframework.http.server.ServerHttpAsyncRequestControl getAsyncRequestControl(
                    ServerHttpResponse resp) {
                return request.getAsyncRequestControl(resp);
            }
        };
    }

    private static String firstToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int comma = raw.indexOf(',');
        String first = (comma >= 0 ? raw.substring(0, comma) : raw).trim();
        return first.isEmpty() ? null : first;
    }
}
