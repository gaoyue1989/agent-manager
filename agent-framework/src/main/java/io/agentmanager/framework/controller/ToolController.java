package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.*;

@RestController
public class ToolController {

    private final OafConfig oafConfig;
    private final AgentRuntimeService agentRuntime;
    private final List<SkillManager.SkillInfo> loadedSkills;
    private final SkillManager skillManager;
    private final List<Map<String, Object>> mcpConfigs;
    private final McpManager mcpManager;

    public ToolController(
        OafConfig oafConfig,
        AgentRuntimeService agentRuntime,
        List<SkillManager.SkillInfo> loadedSkills,
        SkillManager skillManager,
        List<Map<String, Object>> mcpConfigs,
        McpManager mcpManager
    ) {
        this.oafConfig = oafConfig;
        this.agentRuntime = agentRuntime;
        this.loadedSkills = loadedSkills;
        this.skillManager = skillManager;
        this.mcpConfigs = mcpConfigs;
        this.mcpManager = mcpManager;
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> listSkills() {
        return skillManager.getSkillSummaries(loadedSkills);
    }

    @GetMapping("/mcp")
    public List<Map<String, Object>> listMcp() {
        return mcpManager.getMcpSummaries(mcpConfigs);
    }

    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        return Map.of(
            "builtin", oafConfig.tools(),
            "custom", List.of(),
            "mcp", List.of()
        );
    }
}
