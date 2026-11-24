package io.agentmanager.framework.browser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;

import org.springframework.stereotype.Component;

@Component
public class JdkVncUpstreamConnector implements VncUpstreamConnector {

    public static final String API_KEY_HEADER = "OPEN-SANDBOX-API-KEY";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @Override
    public WebSocket connect(URI uri, String apiKey, String subprotocol, WebSocket.Listener listener)
            throws Exception {
        var builder = httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header(API_KEY_HEADER, apiKey);
        }
        if (subprotocol != null && !subprotocol.isBlank()) {
            builder.subprotocols(subprotocol);
        }
        return builder.buildAsync(uri, listener).get();
    }
}
