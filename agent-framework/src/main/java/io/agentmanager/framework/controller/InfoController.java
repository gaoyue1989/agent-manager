package io.agentmanager.framework.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.AgentRuntimeService;

@RestController
public class InfoController {

    private final OafConfig oafConfig;
    private final AgentRuntimeService agentRuntime;

    public InfoController(OafConfig oafConfig, AgentRuntimeService agentRuntime) {
        this.oafConfig = oafConfig;
        this.agentRuntime = agentRuntime;
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
            "agent", oafConfig.name(),
            "description", oafConfig.description(),
            "version", oafConfig.version(),
            "protocols", Map.of("a2a", "1.0.0", "a2ui", "v0.8", "oaf", "v0.8.0"),
            "oaf", Map.of(
                "tools", oafConfig.tools(),
                "skills", oafConfig.skills().size(),
                "mcp", oafConfig.mcpServers().size(),
                "sub_agents", oafConfig.subAgents().size()
            ),
            "endpoints", Map.of(
                "agent_card", "/.well-known/agent-card.json",
                "jsonrpc", "/",
                "threads", "/threads",
                "health", "/health",
                "debug", "/debug"
            ),
            "engine", "AgentScope Java 2.0"
        );
    }

    @GetMapping("/system-prompt")
    public Map<String, Object> systemPrompt() {
        return Map.of(
            "system_prompt", agentRuntime.buildSystemPrompt(),
            "base_prompt", oafConfig.systemPrompt()
        );
    }
}
