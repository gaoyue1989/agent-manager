package io.agentmanager.framework.browser;

import java.net.URI;
import java.net.http.WebSocket;

public interface VncUpstreamConnector {

    WebSocket connect(URI uri, String apiKey, String subprotocol, WebSocket.Listener listener)
        throws Exception;
}
