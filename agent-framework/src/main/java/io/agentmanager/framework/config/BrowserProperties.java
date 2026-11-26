package io.agentmanager.framework.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.browser")
public record BrowserProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("60") long takeoverTtlMinutes,
    @DefaultValue("6080") int viewPort,
    @DefaultValue("/websockify") String viewPath,
    @DefaultValue("60") long vncTicketTtlSeconds,
    @DefaultValue List<String> allowedOriginPatterns
) {

    public BrowserProperties {
        if (allowedOriginPatterns != null) {
            for (var raw : allowedOriginPatterns) {
                if (raw == null) {
                    continue;
                }
                var trimmed = raw.trim();
                if (!trimmed.isEmpty() && isUnconstrainedWildcard(trimmed)) {
                    throw new IllegalArgumentException(
                        "Wildcard browser origin pattern without host constraint is not allowed: " + trimmed);
                }
            }
        }
    }

    public static boolean isUnconstrainedWildcard(String pattern) {
        String s = pattern.trim();
        if ("*".equals(s)) {
            return true;
        }
        int schemeSep = s.indexOf("://");
        String hostPort = schemeSep >= 0 ? s.substring(schemeSep + 3) : s;
        int slash = hostPort.indexOf('/');
        if (slash >= 0) {
            hostPort = hostPort.substring(0, slash);
        }
        int colon = hostPort.indexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        return host.isEmpty() || host.replace("*", "").replace(".", "").isEmpty();
    }

    public long takeoverTtlSeconds() {
        return takeoverTtlMinutes * 60L;
    }

    public List<String> originPatterns() {
        return allowedOriginPatterns.stream()
            .filter(p -> p != null)
            .map(String::trim)
            .filter(p -> !p.isEmpty())
            .toList();
    }
}
