package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.browser")
public record BrowserProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("60") long takeoverTtlMinutes,
    @DefaultValue("6080") int viewPort,
    @DefaultValue("/websockify") String viewPath
) {

    public long takeoverTtlSeconds() {
        return takeoverTtlMinutes * 60L;
    }
}
