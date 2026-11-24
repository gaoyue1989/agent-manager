package io.agentmanager.framework.browser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import io.agentmanager.framework.config.BrowserProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.sandbox.opensandbox.SandboxLocator;
import io.agentmanager.framework.service.SessionUserStore;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnProperty(prefix = "agent.browser", name = "enabled", havingValue = "true")
public class BrowserViewWebSocketConfig implements WebSocketConfigurer {

    private final BrowserViewWebSocketHandler handler;
    private final BrowserViewHandshakeInterceptor interceptor;

    public BrowserViewWebSocketConfig(BrowserViewWebSocketHandler handler,
                                      BrowserViewHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Bean
    public BrowserViewWebSocketHandler browserViewWebSocketHandler(BrowserProperties props,
                                                                   SandboxConfig sandboxConfig,
                                                                   SandboxLocator sandboxLocator,
                                                                   VncUpstreamConnector connector) {
        return new BrowserViewWebSocketHandler(props, sandboxConfig, sandboxLocator, connector);
    }

    @Bean
    public BrowserViewHandshakeInterceptor browserViewHandshakeInterceptor(SessionUserStore sessionUserStore) {
        return new BrowserViewHandshakeInterceptor(sessionUserStore);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/threads/{sessionId}/browser/vnc")
            .addInterceptors(interceptor)
            .setAllowedOriginPatterns("*");
    }
}
