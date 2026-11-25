package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import io.agentmanager.framework.config.BrowserProperties;

class BrowserViewWebSocketConfigTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BrowserViewWebSocketHandler> handlerProvider() {
        ObjectProvider<BrowserViewWebSocketHandler> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(mock(BrowserViewWebSocketHandler.class));
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BrowserViewHandshakeInterceptor> interceptorProvider() {
        ObjectProvider<BrowserViewHandshakeInterceptor> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(mock(BrowserViewHandshakeInterceptor.class));
        return provider;
    }

    private static WebSocketHandlerRegistration register(List<String> allowedOriginPatterns) {
        var registry = mock(WebSocketHandlerRegistry.class);
        var registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(any(WebSocketHandler.class), any(String[].class))).thenReturn(registration);
        when(registration.addInterceptors(any(HandshakeInterceptor[].class))).thenReturn(registration);
        var props = new BrowserProperties(true, 60, 6080, "/websockify", 60, allowedOriginPatterns);
        new BrowserViewWebSocketConfig(handlerProvider(), interceptorProvider(), props)
            .registerWebSocketHandlers(registry);
        return registration;
    }

    @Test
    void wildcardOriginPatternsMustNotBeRegisteredByDefault() {
        var registration = register(List.of());

        verify(registration, never()).setAllowedOriginPatterns(any(String[].class));
    }

    @Test
    void configuredOriginPatternsShouldBeRegistered() {
        var registration = register(List.of("http://demo.example:5188", "https://console.example"));

        var captor = ArgumentCaptor.forClass(String[].class);
        verify(registration).setAllowedOriginPatterns(captor.capture());
        assertArrayEquals(new String[] {"http://demo.example:5188", "https://console.example"},
            captor.getValue());
    }

    @Test
    void blankOriginPatternsShouldFallBackToSameOrigin() {
        var registration = register(List.of("", " "));

        verify(registration, never()).setAllowedOriginPatterns(any(String[].class));
    }
}
