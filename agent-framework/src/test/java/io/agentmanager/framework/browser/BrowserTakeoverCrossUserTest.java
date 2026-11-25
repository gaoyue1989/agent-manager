package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import io.agentmanager.framework.config.BrowserProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.controller.ChatStreamController;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.sandbox.opensandbox.SandboxBindingStore;
import io.agentmanager.framework.sandbox.opensandbox.SandboxLifecycleManager;
import io.agentmanager.framework.sandbox.opensandbox.SandboxLocator;
import io.agentmanager.framework.service.SandboxRuntime;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.TurnStopService;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;

class BrowserTakeoverCrossUserTest {

    private static final String VICTIM = "alice";
    private static final String ATTACKER = "bob";
    private static final String VICTIM_SANDBOX = "sbx-alice-secret-123";

    private SessionUserStore sessionUserStore;
    private SessionSandboxStateStore stateStore;
    private SandboxBindingStore bindingStore;
    private BrowserControlStore controlStore;
    private TurnStopService turnStopService;
    private ChatStreamController chatStreamController;
    private SandboxLocator locator;
    private BrowserControlController controller;
    private BrowserViewHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        sessionUserStore = mock(SessionUserStore.class);
        stateStore = mock(SessionSandboxStateStore.class);
        bindingStore = mock(SandboxBindingStore.class);
        controlStore = mock(BrowserControlStore.class);
        turnStopService = mock(TurnStopService.class);
        chatStreamController = mock(ChatStreamController.class);

        var oafConfig = new OafConfig("browser-takeover", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);
        var sandboxConfig = new SandboxConfig(false, "img", 60, 30, 1024, 1, List.of("/e"),
            Duration.ofMillis(100), true, true, 900, true, true, true, false, 500, 30,
            new SandboxConfig.OpenSandboxConfig("server:8090", "api-key"));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<SandboxBindingStore> storeProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(storeProvider.getIfAvailable()).thenReturn(bindingStore);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<SandboxLifecycleManager> lifecycleProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(lifecycleProvider.getIfAvailable()).thenReturn(null);
        locator = new SandboxLocator(stateStore, oafConfig, sandboxConfig, storeProvider, lifecycleProvider);

        var sandboxRuntime = mock(SandboxRuntime.class);
        when(sandboxRuntime.enabled()).thenReturn(true);
        var service = new BrowserControlService(controlStore, turnStopService, sandboxRuntime, locator);
        controller = new BrowserControlController(service, sessionUserStore, chatStreamController);
        interceptor = new BrowserViewHandshakeInterceptor(sessionUserStore);

        when(stateStore.load(any())).thenReturn(Optional.of(
            "{\"type\":\"opensandbox\",\"sandboxId\":\"" + VICTIM_SANDBOX + "\"}"));
        when(bindingStore.ownedByUserKey(anyString(), anyString())).thenReturn(false);
    }

    private ServerHttpRequest request(String sessionId, String headerUserId) {
        var request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("https://entry/threads/" + sessionId + "/browser/vnc"));
        var headers = new HttpHeaders();
        if (headerUserId != null) {
            headers.set("X-User-Id", headerUserId);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    @Test
    void findingD01_attackerCannotReadVictimSandboxWithoutOwnershipRow() {
        when(sessionUserStore.findUserIdBySession(VICTIM)).thenReturn(null);

        var e = assertThrows(ResponseStatusException.class,
            () -> controller.state(VICTIM, ATTACKER));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());

        var takeover = assertThrows(ResponseStatusException.class,
            () -> controller.takeover(VICTIM, ATTACKER, null));
        assertEquals(HttpStatus.NOT_FOUND, takeover.getStatusCode());

        assertFalse(interceptor.beforeHandshake(request(VICTIM, ATTACKER),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));

        verifyNoInteractions(stateStore);
        verify(controlStore, never()).takeover(anyString(), any(), anyString(), anyString());
        verify(turnStopService, never()).request(anyString(), anyString());
    }

    @Test
    void findingD01_claimedSessionCannotResolveVictimSandboxOrVnc() throws Exception {
        when(sessionUserStore.findUserIdBySession(VICTIM)).thenReturn(ATTACKER);

        var response = controller.state(VICTIM, ATTACKER);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        var sandbox = (Map<?, ?>) response.getBody().get("sandbox");
        assertEquals(false, sandbox.get("ready"));
        assertNull(sandbox.get("sandbox_id"));

        var attributes = new HashMap<String, Object>();
        assertTrue(interceptor.beforeHandshake(request(VICTIM, ATTACKER),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes));

        var session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("ws-attacker");
        when(session.isOpen()).thenReturn(true);
        var handler = new BrowserViewWebSocketHandler(
            new BrowserProperties(true, 60, 6080, "/websockify"),
            new SandboxConfig(false, "img", 60, 30, 1024, 1, List.of("/e"),
                Duration.ofMillis(100), true, true, 900, true, true, true, false, 500, 30,
                new SandboxConfig.OpenSandboxConfig("server:8090", "api-key")),
            locator, mock(VncUpstreamConnector.class));

        handler.afterConnectionEstablished(session);

        var close = org.mockito.ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(close.capture());
        assertEquals(BrowserViewWebSocketHandler.CLOSE_SANDBOX_NOT_FOUND, close.getValue().getCode());
    }

    @Test
    void ownerStillResolvesOwnSandbox() throws Exception {
        when(sessionUserStore.findUserIdBySession("sess-owner")).thenReturn(VICTIM);
        when(bindingStore.ownedByUserKey(VICTIM_SANDBOX, VICTIM)).thenReturn(true);

        var response = controller.state("sess-owner", VICTIM);
        var sandbox = (Map<?, ?>) response.getBody().get("sandbox");
        assertEquals(true, sandbox.get("ready"));
        assertEquals(VICTIM_SANDBOX, sandbox.get("sandbox_id"));
        assertTrue(interceptor.beforeHandshake(request("sess-owner", VICTIM),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));
    }
}
