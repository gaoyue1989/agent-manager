package io.agentmanager.framework.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpManager;
import io.agentmanager.framework.service.McpToolRegistrar;

@RestController
public class ToolController {

    private final OafConfig oafConfig;
    private final AgentRuntimeService agentRuntime;
    private final List<Map<String, Object>> mcpConfigs;
    private final McpManager mcpManager;
    private final McpToolRegistrar mcpToolRegistrar;

    public ToolController(
        OafConfig oafConfig,
        AgentRuntimeService agentRuntime,
        List<Map<String, Object>> mcpConfigs,
        McpManager mcpManager,
        McpToolRegistrar mcpToolRegistrar
    ) {
        this.oafConfig = oafConfig;
        this.agentRuntime = agentRuntime;
        this.mcpConfigs = mcpConfigs;
        this.mcpManager = mcpManager;
        this.mcpToolRegistrar = mcpToolRegistrar;
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> listSkills() {
        return oafConfig.skills().stream()
            .map(s -> Map.<String, Object>of(
                "name", s.name(),
                "description", s.description() != null ? s.description() : "",
                "version", s.version(),
                "source", s.source()
            ))
            .toList();
    }

    @GetMapping("/mcp")
    public List<Map<String, Object>> listMcp() {
        return mcpManager.getMcpSummaries(mcpConfigs);
    }

    /**
     * 工具列表：默认只返回 MCP 业务工具，内置工具需显式 includeInternal=true。
     */
    @GetMapping("/tools")
    public Map<String, Object> listTools(
        @RequestParam(defaultValue = "false") boolean includeInternal
    ) {
        List<Map<String, Object>> tools = new ArrayList<>();

        // 1. MCP 工具（业务工具，默认暴露）
        List<Map<String, Object>> mcpTools = getMcpTools();
        tools.addAll(mcpTools);

        // 2. 内置工具（可选）
        if (includeInternal) {
            oafConfig.tools().stream()
                .map(name -> Map.<String, Object>of("name", name, "category", "internal"))
                .forEach(tools::add);
        }

        return Map.of(
            "tools", tools,
            "totalCount", tools.size(),
            "mcpCount", mcpTools.size()
        );
    }

    private List<Map<String, Object>> getMcpTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var config : mcpConfigs) {
            String serverName = (String) config.getOrDefault("server", "unknown");
            mcpToolRegistrar.getToolsByServer(serverName).stream()
                .map(tool -> Map.<String, Object>of(
                    "name", tool.name(),
                    "server", serverName,
                    "category", "mcp",
                    "description", tool.description() != null ? tool.description() : ""
                ))
                .forEach(result::add);
        }
        return result;
    }
}
