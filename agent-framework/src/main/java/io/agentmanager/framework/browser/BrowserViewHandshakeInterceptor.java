package io.agentmanager.framework.browser;

import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
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

    public BrowserViewHandshakeInterceptor(SessionUserStore sessionUserStore) {
        this.sessionUserStore = sessionUserStore;
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
        String sessionId = PathSafe.sanitize(matcher.group(1));
        String headerUserId = request.getHeaders().getFirst("X-User-Id");
        if (headerUserId == null || headerUserId.isBlank()) {
            headerUserId = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("userId");
        }
        var owner = SessionAccessGuard.establishedOwner(sessionUserStore, sessionId, headerUserId);
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
}
