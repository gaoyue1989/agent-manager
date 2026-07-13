package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent")
public record AgentManagerProperties(
    LLMConfig llm,
    ServerConfig server,
    CheckpointConfig checkpoint,
    @DefaultValue("/config") String configDir
) {
    public record LLMConfig(
        @DefaultValue("") String apiKey,
        @DefaultValue("") String modelId,
        @DefaultValue("") String baseUrl,
        @DefaultValue("openai") String provider,
        @DefaultValue("0.7") double temperature,
        @DefaultValue("4096") int maxTokens,
        @DefaultValue("120") int timeout
    ) {}

    public record ServerConfig(
        @DefaultValue("0.0.0.0") String host,
        @DefaultValue("8100") int port
    ) {}

    public record CheckpointConfig(
        @DefaultValue("jdbc:mysql://127.0.0.1:3307/agent_manager_test") String jdbcUrl,
        @DefaultValue("agent_manager") String username,
        @DefaultValue("Agent@Manager2026") String password
    ) {}
}
