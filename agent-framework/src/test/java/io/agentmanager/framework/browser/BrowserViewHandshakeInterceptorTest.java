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
    private static final String TICKET = "ticket-1";

    private final SessionUserStore store = mock(SessionUserStore.class);
    private final BrowserViewTicketStore ticketStore = mock(BrowserViewTicketStore.class);
    private final BrowserViewHandshakeInterceptor interceptor =
        new BrowserViewHandshakeInterceptor(store, ticketStore);

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

    private boolean handshake(String uri, String headerUserId, HashMap<String, Object> attributes) {
        return interceptor.beforeHandshake(request(uri, headerUserId),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
    }

    private static String vncUri(String sessionId, String ticket) {
        return "https://entry/threads/" + sessionId + "/browser/vnc?ticket=" + ticket;
    }

    @Test
    void ticketForOwnerShouldPassWithSessionAndUserAttributes() {
        when(ticketStore.consume(TICKET)).thenReturn(new BrowserViewTicketStore.Claim(SESSION, USER));
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);
        var attributes = new HashMap<String, Object>();

        assertTrue(handshake(vncUri(SESSION, TICKET), null, attributes));
        assertEquals(SESSION, attributes.get(BrowserViewHandshakeInterceptor.ATTR_SESSION_ID));
        assertEquals(USER, attributes.get(BrowserViewHandshakeInterceptor.ATTR_USER_ID));
    }

    @Test
    void queryParamUserIdMustNotAuthenticate() {
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);

        assertFalse(handshake("https://entry/threads/" + SESSION + "/browser/vnc?userId=" + USER,
            null, new HashMap<>()));
    }

    @Test
    void gatewayHeaderAloneMustNotAuthenticate() {
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);

        assertFalse(handshake("https://entry/threads/" + SESSION + "/browser/vnc",
            USER, new HashMap<>()));
    }

    @Test
    void unknownTicketShouldBeRejected() {
        when(ticketStore.consume("forged")).thenReturn(null);

        assertFalse(handshake(vncUri(SESSION, "forged"), null, new HashMap<>()));
    }

    @Test
    void replayedTicketShouldBeRejected() {
        when(ticketStore.consume(TICKET))
            .thenReturn(new BrowserViewTicketStore.Claim(SESSION, USER))
            .thenReturn(null);
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);

        assertTrue(handshake(vncUri(SESSION, TICKET), null, new HashMap<>()));
        assertFalse(handshake(vncUri(SESSION, TICKET), null, new HashMap<>()));
    }

    @Test
    void ticketForAnotherSessionShouldBeRejected() {
        when(ticketStore.consume(TICKET))
            .thenReturn(new BrowserViewTicketStore.Claim("another-session", USER));

        assertFalse(handshake(vncUri(SESSION, TICKET), null, new HashMap<>()));
    }

    @Test
    void headerConflictingWithTicketShouldBeRejected() {
        when(ticketStore.consume(TICKET)).thenReturn(new BrowserViewTicketStore.Claim(SESSION, USER));
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);

        assertFalse(handshake(vncUri(SESSION, TICKET), "someone-else", new HashMap<>()));
    }

    @Test
    void foreignOwnerShouldBeRejected() {
        when(ticketStore.consume(TICKET)).thenReturn(new BrowserViewTicketStore.Claim(SESSION, USER));
        when(store.findUserIdBySession(SESSION)).thenReturn("someone-else");

        assertFalse(handshake(vncUri(SESSION, TICKET), null, new HashMap<>()));
    }

    @Test
    void unexpectedPathShouldBeRejected() {
        assertFalse(handshake("https://entry/threads/" + SESSION + "/other?ticket=" + TICKET,
            null, new HashMap<>()));
    }

    @Test
    void unclaimedSessionShouldBeRejected() {
        when(ticketStore.consume(TICKET)).thenReturn(new BrowserViewTicketStore.Claim(SESSION, USER));
        when(store.findUserIdBySession(SESSION)).thenReturn(null);

        assertFalse(handshake(vncUri(SESSION, TICKET), null, new HashMap<>()));
    }

    @Test
    void placeholderSessionShouldBeRejected() {
        when(ticketStore.consume(TICKET)).thenReturn(new BrowserViewTicketStore.Claim(SESSION, USER));
        when(store.findUserIdBySession(SESSION)).thenReturn(SessionUserStore.PLACEHOLDER_USER);

        assertFalse(handshake(vncUri(SESSION, TICKET), null, new HashMap<>()));
    }

    @Test
    void crossOriginHandshakeShouldBeRejectedBeforeConsumingTicket() {
        var req = mock(org.springframework.http.server.ServerHttpRequest.class);
        var resp = mock(org.springframework.http.server.ServerHttpResponse.class);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setOrigin("https://evil.example");
        when(req.getURI()).thenReturn(java.net.URI.create("http://127.0.0.1:8911/threads/" + SESSION + "/browser/vnc?ticket=" + TICKET));
        when(req.getHeaders()).thenReturn(headers);

        assertFalse(interceptor.beforeHandshake(req, resp, mock(org.springframework.web.socket.WebSocketHandler.class), new HashMap<>()));
        org.mockito.Mockito.verify(ticketStore, org.mockito.Mockito.never()).consume(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void forwardedHttpsOriginWithoutForwardedPortShouldMatchStandardHttpsOrigin() {
        when(ticketStore.consume(TICKET)).thenReturn(new BrowserViewTicketStore.Claim(SESSION, USER));
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);
        var req = mock(org.springframework.http.server.ServerHttpRequest.class);
        var resp = mock(org.springframework.http.server.ServerHttpResponse.class);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setOrigin("https://app.example.com");
        headers.add("X-Forwarded-Proto", "https");
        headers.add("X-Forwarded-Host", "app.example.com");
        when(req.getURI()).thenReturn(java.net.URI.create("http://10.244.0.9:8100/threads/" + SESSION + "/browser/vnc?ticket=" + TICKET));
        when(req.getHeaders()).thenReturn(headers);

        assertTrue(interceptor.beforeHandshake(req, resp, mock(org.springframework.web.socket.WebSocketHandler.class), new HashMap<>()));
    }

    @Test
    void forwardedHttpHostWithExplicitPortShouldMatchOrigin() {
        when(ticketStore.consume(TICKET)).thenReturn(new BrowserViewTicketStore.Claim(SESSION, USER));
        when(store.findUserIdBySession(SESSION)).thenReturn(USER);
        var req = mock(org.springframework.http.server.ServerHttpRequest.class);
        var resp = mock(org.springframework.http.server.ServerHttpResponse.class);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setOrigin("http://100.66.1.11:8911");
        headers.add("X-Forwarded-Proto", "http");
        headers.add("X-Forwarded-Host", "100.66.1.11:8911");
        when(req.getURI()).thenReturn(java.net.URI.create("http://10.244.0.9:8100/threads/" + SESSION + "/browser/vnc?ticket=" + TICKET));
        when(req.getHeaders()).thenReturn(headers);

        assertTrue(interceptor.beforeHandshake(req, resp, mock(org.springframework.web.socket.WebSocketHandler.class), new HashMap<>()));
    }
}
