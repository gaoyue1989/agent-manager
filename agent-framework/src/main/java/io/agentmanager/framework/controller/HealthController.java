package io.agentmanager.framework.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.AgentRuntimeService;

@RestController
public class HealthController {

    private final OafConfig oafConfig;
    private final AgentRuntimeService agentRuntime;
    private final AgentManagerProperties props;

    public HealthController(OafConfig oafConfig, AgentRuntimeService agentRuntime, AgentManagerProperties props) {
        this.oafConfig = oafConfig;
        this.agentRuntime = agentRuntime;
        this.props = props;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        var llmValid = !props.llm().apiKey().isEmpty()
            && !props.llm().modelId().isEmpty()
            && !props.llm().baseUrl().isEmpty();

        return Map.of(
            "status", "healthy",
            "agent", oafConfig.name(),
            "version", oafConfig.version(),
            "slug", oafConfig.slug(),
            "engine", "AgentScope Java 2.0",
            "llm_configured", llmValid,
            "tenant_prefix", agentRuntime.tenantPrefix()
        );
    }
}
