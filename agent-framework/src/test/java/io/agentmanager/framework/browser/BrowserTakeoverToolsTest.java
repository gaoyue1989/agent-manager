package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentscope.core.agent.RuntimeContext;

class BrowserTakeoverToolsTest {

    @Test
    void requestTakeoverStripsConfiguredTenantPrefixAndCallsControl() {
        var control = mock(BrowserControlService.class);
        var store = mock(SessionUserStore.class);
        var oaf = mock(OafConfig.class);
        when(oaf.slug()).thenReturn("acme-browser");
        when(store.findUserIdBySession("sess-1")).thenReturn("u-1001");

        var tools = new BrowserTakeoverTools(control, store, oaf);
        var ctx = RuntimeContext.builder()
            .sessionId("acme-browser__sess-1")
            .userId("vendor-key")
            .build();

        String reply = tools.requestTakeover(ctx, "need 2FA");

        verify(control).takeover("sess-1", "u-1001", "need 2FA", BrowserControlService.TRIGGER_AGENT);
        assertTrue(reply.contains("Browser control has been handed to the human operator"));
        assertTrue(reply.contains("need 2FA"));
    }

    @Test
    void requestTakeoverPreservesUserDoubleUnderscoreSessionId() {
        var control = mock(BrowserControlService.class);
        var store = mock(SessionUserStore.class);
        var oaf = mock(OafConfig.class);
        when(oaf.slug()).thenReturn("acme-browser");
        when(store.findUserIdBySession("proj__order-1")).thenReturn("u-2002");

        var tools = new BrowserTakeoverTools(control, store, oaf);
        var ctx = RuntimeContext.builder()
            .sessionId("proj__order-1")
            .userId("u-2002")
            .build();

        tools.requestTakeover(ctx, null);

        verify(control).takeover("proj__order-1", "u-2002", null, BrowserControlService.TRIGGER_AGENT);
    }

    @Test
    void requestTakeoverReturnsErrorWhenSessionCannotBeResolved() {
        var control = mock(BrowserControlService.class);
        var tools = new BrowserTakeoverTools(control, mock(SessionUserStore.class));

        String reply = tools.requestTakeover(null, "captcha");

        assertTrue(reply.startsWith("Error: cannot resolve the current session"));
        verifyNoInteractions(control);
    }

    @Test
    void requestTakeoverReturnsErrorWhenControlStoreUnavailable() {
        var control = mock(BrowserControlService.class);
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("s-1")).thenReturn("u-1");
        when(control.takeover("s-1", "u-1", "captcha", BrowserControlService.TRIGGER_AGENT))
            .thenThrow(new BrowserControlUnavailableException("redis down", new RuntimeException("io")));

        var tools = new BrowserTakeoverTools(control, store);
        var ctx = RuntimeContext.builder().sessionId("s-1").userId("u-1").build();

        String reply = tools.requestTakeover(ctx, "captcha");

        assertTrue(reply.contains("browser control store unavailable"));
        assertTrue(reply.contains("redis down"));
    }
}
