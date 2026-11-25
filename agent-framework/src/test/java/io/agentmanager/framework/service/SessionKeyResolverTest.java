package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;

class SessionKeyResolverTest {

    private final SessionUserStore store = mock(SessionUserStore.class);
    private final SessionKeyResolver resolver = new SessionKeyResolver(store);

    @Test
    void a2aMustNeverLookUpUserIdAsSessionKey() {
        when(store.findUserIdBySession("alice-private-sess")).thenReturn(null);
        when(store.findUserIdBySession("u-1001")).thenReturn("u-2002");

        var ctx = RuntimeContext.builder().sessionId("alice-private-sess").userId("u-1001").build();

        assertEquals("u-1001", resolver.realUserId(ctx));
        assertEquals("alice-private-sess", resolver.canonicalSessionId(ctx));
        verify(store, never()).findUserIdBySession("u-1001");
    }

    @Test
    void a2aSessionIdRowIsAuthoritative() {
        when(store.findUserIdBySession("alice-private-sess")).thenReturn("u-1001");

        var ctx = RuntimeContext.builder().sessionId("alice-private-sess").userId("u-1001").build();

        assertEquals("u-1001", resolver.realUserId(ctx));
        assertEquals("alice-private-sess", resolver.canonicalSessionId(ctx));
    }

    @Test
    void channelMustUsePeerRowNotGatewayHashRow() {
        when(store.findUserIdBySession("gw-3f20f08c5499")).thenReturn("u-2002");
        when(store.findUserIdBySession("front-sid")).thenReturn("u-1001");

        var ctx = RuntimeContext.builder().sessionId("gw-3f20f08c5499").userId("front-sid").build();

        assertEquals("u-1001", resolver.realUserId(ctx));
        assertEquals("front-sid", resolver.canonicalSessionId(ctx));
        verify(store, never()).findUserIdBySession("gw-3f20f08c5499");
    }

    @Test
    void channelPeerWithoutRowFallsBackToPeer() {
        when(store.findUserIdBySession("front-sid")).thenReturn(null);

        var ctx = RuntimeContext.builder().sessionId("gw-3f20f08c5499").userId("front-sid").build();

        assertEquals("front-sid", resolver.realUserId(ctx));
        assertEquals("front-sid", resolver.canonicalSessionId(ctx));
    }

    @Test
    void directInvokeKeepsSessionIdAndVendorKey() {
        var ctx = RuntimeContext.builder()
            .sessionId("acme-test-agent__thread-1").userId("vendor-key").build();

        assertEquals("acme-test-agent__thread-1", resolver.canonicalSessionId(ctx));
        assertEquals("vendor-key", resolver.realUserId(ctx));
    }

    @Test
    void resolutionIsMemoizedPerTurn() {
        when(store.findUserIdBySession("s-1")).thenReturn("u-1");

        var ctx = RuntimeContext.builder().sessionId("s-1").userId("u-1").build();
        resolver.realUserId(ctx);
        resolver.canonicalSessionId(ctx);
        resolver.realUserId(ctx);

        verify(store, times(1)).findUserIdBySession("s-1");
    }
}
