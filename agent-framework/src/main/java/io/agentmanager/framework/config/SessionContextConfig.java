package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.session-context")
public record SessionContextConfig(
    @DefaultValue("30000") int maxTotalChars
) {
}
