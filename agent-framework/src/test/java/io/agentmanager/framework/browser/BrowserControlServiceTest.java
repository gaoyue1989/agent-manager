package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.sandbox.opensandbox.SandboxLocator;
import io.agentmanager.framework.service.SandboxRuntime;
import io.agentmanager.framework.service.TurnStopService;
import io.agentscope.harness.agent.IsolationScope;

class BrowserControlServiceTest {

    private static final String SESSION = "sess-1";
    private static final String USER = "u-1";

    private BrowserControlStore store;
    private TurnStopService turnStopService;
    private SandboxRuntime sandboxRuntime;
    private SandboxLocator sandboxLocator;
    private BrowserControlService service;

    @BeforeEach
    void setUp() {
        store = mock(BrowserControlStore.class);
        turnStopService = mock(TurnStopService.class);
        sandboxRuntime = mock(SandboxRuntime.class);
        sandboxLocator = mock(SandboxLocator.class);
        service = new BrowserControlService(store, turnStopService, sandboxRuntime, sandboxLocator);
    }

    @Test
    void controlShouldDefaultToAgentWhenNoLock() {
        when(store.find(SESSION)).thenReturn(null);
        var view = service.control(SESSION);
        assertEquals("AGENT", view.owner());
        assertNull(view.reason());
        assertEquals(0, view.expiresInSeconds());
    }

    @Test
    void controlShouldExposeHumanLock() {
        when(store.find(SESSION)).thenReturn(new BrowserControlStore.ControlState("2fa", "u-1", "agent", 1700000000000L));
        when(store.ttlSeconds(SESSION)).thenReturn(600L);
        var view = service.control(SESSION);
        assertEquals("HUMAN", view.owner());
        assertEquals("2fa", view.reason());
        assertEquals("agent", view.trigger());
        assertEquals(600L, view.expiresInSeconds());
    }

    @Test
    void userTakeoverShouldLockAndRequestTurnStop() {
        when(turnStopService.request(SESSION, "browser_takeover"))
            .thenReturn(TurnStopService.Result.of(TurnStopService.Outcome.REQUESTED, "rid-1"));
        when(store.find(SESSION)).thenReturn(new BrowserControlStore.ControlState("r", "u-1", "user", 1L));
        when(store.ttlSeconds(SESSION)).thenReturn(3600L);

        var result = service.takeover(SESSION, USER, "r", BrowserControlService.TRIGGER_USER);

        verify(store).takeover(SESSION, "r", USER, "user");
        verify(turnStopService).request(SESSION, "browser_takeover");
        assertEquals("REQUESTED", result.stopOutcome());
        assertEquals("HUMAN", result.control().owner());
    }

    @Test
    void agentTakeoverShouldNotStopTheTurn() {
        when(store.find(SESSION)).thenReturn(new BrowserControlStore.ControlState("login", "u-1", "agent", 1L));
        when(store.ttlSeconds(SESSION)).thenReturn(3600L);

        var result = service.takeover(SESSION, USER, "login", BrowserControlService.TRIGGER_AGENT);

        verify(store).takeover(SESSION, "login", USER, "agent");
        verify(turnStopService, never()).request(anyString(), anyString());
        assertNull(result.stopOutcome());
    }

    @Test
    void stopRequestFailureShouldBeReportedAsUnavailable() {
        when(turnStopService.request(anyString(), anyString())).thenThrow(new IllegalStateException("db down"));
        when(store.find(SESSION)).thenReturn(new BrowserControlStore.ControlState("r", "u-1", "user", 1L));
        when(store.ttlSeconds(SESSION)).thenReturn(3600L);

        var result = service.takeover(SESSION, USER, "r", BrowserControlService.TRIGGER_USER);
        assertEquals("UNAVAILABLE", result.stopOutcome());
    }

    @Test
    void gateReadShouldFailOpenWhenStoreUnavailable() {
        when(store.isHumanControlled(SESSION))
            .thenThrow(new BrowserControlUnavailableException("down", new IllegalStateException("down")));
        assertFalse(service.isHumanControlled(SESSION));
    }

    @Test
    void stateShouldReportSandboxReadinessWithoutSideEffects() {
        when(store.find(SESSION)).thenReturn(null);
        when(sandboxRuntime.enabled()).thenReturn(true);
        when(sandboxLocator.sandboxIdOwned(USER, SESSION, IsolationScope.USER)).thenReturn("sb-1");

        var state = service.state(USER, SESSION);
        var sandbox = (java.util.Map<?, ?>) state.get("sandbox");
        assertEquals(true, sandbox.get("ready"));
        assertEquals("sb-1", sandbox.get("sandbox_id"));
        assertEquals("/threads/" + SESSION + "/browser/vnc", state.get("vnc_path"));
        verify(sandboxLocator, never()).sandboxId(anyString(), anyString(), any());
    }

    @Test
    void stateShouldExplainDisabledSandbox() {
        when(store.find(SESSION)).thenReturn(null);
        when(sandboxRuntime.enabled()).thenReturn(false);

        var state = service.state(USER, SESSION);
        var sandbox = (java.util.Map<?, ?>) state.get("sandbox");
        assertEquals(false, sandbox.get("ready"));
        assertEquals("sandbox_disabled", sandbox.get("reason"));
    }

    @Test
    void holdSignalShouldMirrorHumanControl() {
        when(store.isHumanControlled(SESSION)).thenReturn(true);
        assertTrue(service.isHeld(SESSION));
        assertTrue(service.isHumanControlled(SESSION));
        verify(store, org.mockito.Mockito.times(2)).isHumanControlled(eq(SESSION));
    }

    @Test
    void isHeldShouldMatchA2AUserKeyWhenSessionIsUnderTakeover637() {
        when(store.heldSessionsForUser("u-alice")).thenReturn(java.util.Set.of("a2a-sess-1"));
        when(store.isHumanControlled("a2a-sess-1")).thenReturn(true);
        when(store.isHumanControlled("u-alice")).thenReturn(false);
        assertTrue(service.isHeld("u-alice"));

        when(store.isHumanControlled("a2a-sess-1"))
            .thenThrow(new BrowserControlUnavailableException("redis timeout", new RuntimeException()));
        assertFalse(service.isHeld("u-alice"));
        verify(store, org.mockito.Mockito.never()).removeUserHeldSession("u-alice", "a2a-sess-1");

        org.mockito.Mockito.doReturn(false).when(store).isHumanControlled("a2a-sess-1");
        assertFalse(service.isHeld("u-alice"));
        verify(store).removeUserHeldSession("u-alice", "a2a-sess-1");
    }
}
