package io.agentmanager.framework.browser;

import org.springframework.beans.factory.ObjectProvider;
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

    private final ObjectProvider<BrowserViewWebSocketHandler> handler;
    private final ObjectProvider<BrowserViewHandshakeInterceptor> interceptor;
    private final BrowserProperties props;

    public BrowserViewWebSocketConfig(ObjectProvider<BrowserViewWebSocketHandler> handler,
                                      ObjectProvider<BrowserViewHandshakeInterceptor> interceptor,
                                      BrowserProperties props) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.props = props;
    }

    @Bean
    public BrowserViewWebSocketHandler browserViewWebSocketHandler(BrowserProperties props,
                                                                   SandboxConfig sandboxConfig,
                                                                   SandboxLocator sandboxLocator,
                                                                   VncUpstreamConnector connector) {
        return new BrowserViewWebSocketHandler(props, sandboxConfig, sandboxLocator, connector);
    }

    @Bean
    public BrowserViewHandshakeInterceptor browserViewHandshakeInterceptor(
            SessionUserStore sessionUserStore, BrowserViewTicketStore ticketStore) {
        return new BrowserViewHandshakeInterceptor(sessionUserStore, ticketStore, props.originPatterns());
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(handler.getObject(), "/threads/{sessionId}/browser/vnc")
            .addInterceptors(interceptor.getObject());
        var patterns = props.originPatterns();
        if (!patterns.isEmpty()) {
            registration.setAllowedOriginPatterns(patterns.toArray(String[]::new));
        }
    }
}
