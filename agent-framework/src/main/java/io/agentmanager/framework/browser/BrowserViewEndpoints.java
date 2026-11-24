package io.agentmanager.framework.browser;

import java.net.URI;

public final class BrowserViewEndpoints {

    private BrowserViewEndpoints() {
    }

    public static URI upstream(String endpoint, String viewPath) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("empty sandbox endpoint");
        }
        String base = endpoint.trim();
        String lower = base.toLowerCase();
        if (lower.startsWith("https://")) {
            base = "wss://" + base.substring("https://".length());
        } else if (lower.startsWith("http://")) {
            base = "ws://" + base.substring("http://".length());
        } else if (!lower.startsWith("ws://") && !lower.startsWith("wss://")) {
            base = "ws://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = viewPath == null ? "" : viewPath.trim();
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return URI.create(base + path);
    }
}
