package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpManager;

@RestController
public class InfoController {

    private final OafConfig oafConfig;
    private final AgentRuntimeService agentRuntime;
    private final McpManager mcpManager;
    private final List<Map<String, Object>> mcpConfigs;

    public InfoController(
        OafConfig oafConfig,
        AgentRuntimeService agentRuntime,
        McpManager mcpManager,
        List<Map<String, Object>> mcpConfigs
    ) {
        this.oafConfig = oafConfig;
        this.agentRuntime = agentRuntime;
        this.mcpManager = mcpManager;
        this.mcpConfigs = mcpConfigs;
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
            "agent", oafConfig.name(),
            "slug", oafConfig.slug(),
            "version", oafConfig.version(),
            "description", oafConfig.description(),
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
                "debug", "/debug",
                "metadata", "/metadata"
            ),
            "engine", "AgentScope Java 2.0"
        );
    }

    /**
     * 完整 Agent 元数据端点：skills 返回对象数组而非数量。
     */
    @GetMapping("/metadata")
    public Map<String, Object> getMetadata(
        @RequestParam(defaultValue = "false") boolean includeDetails
    ) {
        var result = new LinkedHashMap<String, Object>();

        // 基本信息
        result.put("name", oafConfig.name());
        result.put("slug", oafConfig.slug());
        result.put("version", oafConfig.version());
        result.put("description", oafConfig.description());

        // 能力信息
        result.put("skills", oafConfig.skills().stream()
            .map(s -> Map.of(
                "name", s.name(),
                "description", s.description() != null ? s.description() : ""
            ))
            .toList());
        result.put("mcp", mcpManager.getMcpSummaries(mcpConfigs));
        result.put("protocols", Map.of("a2a", "1.0.0", "a2ui", "v0.8", "oaf", "v0.8.0"));

        // 详细信息（可选）
        if (includeDetails) {
            result.put("tools", oafConfig.tools());
            result.put("subAgents", oafConfig.subAgents());
            result.put("model", oafConfig.model());
            result.put("endpoints", Map.of(
                "agent_card", "/.well-known/agent-card.json",
                "jsonrpc", "/",
                "threads", "/threads",
                "health", "/health",
                "debug", "/debug",
                "metadata", "/metadata"
            ));
        }

        return result;
    }

    @GetMapping("/system-prompt")
    public Map<String, Object> systemPrompt() {
        return Map.of(
            "system_prompt", agentRuntime.buildSystemPrompt(),
            "base_prompt", oafConfig.systemPrompt()
        );
    }
}
