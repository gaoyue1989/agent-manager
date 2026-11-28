package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.stage-progress")
public record StageProgressConfig(
    @DefaultValue("true") boolean enabled
) {
    public static StageProgressConfig defaults() {
        return new StageProgressConfig(true);
    }
}
