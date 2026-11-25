package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import io.agentmanager.framework.service.SessionUserStore;

class BrowserViewHandshakeInterceptorTest {

    private static final String SESSION = "sess-1";
    private static final String USER = "u-1";

    private final SessionUserStore store = mock(SessionUserStore.class);
    private final BrowserViewHandshakeInterceptor interceptor = new BrowserViewHandshakeInterceptor(store);

    private ServerHttpRequest request(String uri, String headerUserId) {
        var request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        var headers = new HttpHeaders();
        if (headerUserId != null) {
            headers.set("X-User-Id", headerUserId);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    @Test
    void ownerShouldPassWithSessionAndUserAttributes() {
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);
        var attributes = new HashMap<String, Object>();

        assertTrue(interceptor.beforeHandshake(
            request("https://entry/agent/demo/threads/" + SESSION + "/browser/vnc?afterSeq=1", USER),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes));
        assertEquals(SESSION, attributes.get(BrowserViewHandshakeInterceptor.ATTR_SESSION_ID));
        assertEquals(USER, attributes.get(BrowserViewHandshakeInterceptor.ATTR_USER_ID));
    }

    @Test
    void queryParamUserIdShouldCoverBrowserWebSocketApi() {
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);
        var attributes = new HashMap<String, Object>();

        assertTrue(interceptor.beforeHandshake(
            request("https://entry/threads/" + SESSION + "/browser/vnc?userId=" + USER, null),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes));
        assertEquals(USER, attributes.get(BrowserViewHandshakeInterceptor.ATTR_USER_ID));
    }

    @Test
    void foreignUserShouldBeRejected() {
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);
        assertFalse(interceptor.beforeHandshake(
            request("https://entry/threads/" + SESSION + "/browser/vnc", "someone-else"),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));
    }

    @Test
    void unexpectedPathShouldBeRejected() {
        assertFalse(interceptor.beforeHandshake(
            request("https://entry/threads/" + SESSION + "/other", USER),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));
    }

    @Test
    void unclaimedSessionShouldBeRejected() {
        when(store.findUserIdBySession(SESSION)).thenReturn(null);
        assertFalse(interceptor.beforeHandshake(
            request("https://entry/threads/" + SESSION + "/browser/vnc", "someone-else"),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));
    }

    @Test
    void placeholderSessionShouldBeRejected() {
        when(store.findUserIdBySession(SESSION)).thenReturn(SessionUserStore.PLACEHOLDER_USER);
        assertFalse(interceptor.beforeHandshake(
            request("https://entry/threads/" + SESSION + "/browser/vnc", "someone-else"),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));
    }
}
