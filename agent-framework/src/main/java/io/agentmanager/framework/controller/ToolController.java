package io.agentmanager.framework.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.InternalToolRegistry;
import io.agentmanager.framework.service.McpManager;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SkillCatalogService;

@RestController
public class ToolController {

    private final OafConfigHolder oafConfigHolder;
    private final AgentRuntimeService agentRuntime;
    private final McpManager mcpManager;
    private final McpToolRegistrar mcpToolRegistrar;
    private final SkillCatalogService skillCatalog;
    private final InternalToolRegistry internalToolRegistry;

    public ToolController(
        OafConfigHolder oafConfigHolder,
        AgentRuntimeService agentRuntime,
        McpManager mcpManager,
        McpToolRegistrar mcpToolRegistrar,
        SkillCatalogService skillCatalog,
        InternalToolRegistry internalToolRegistry
    ) {
        this.oafConfigHolder = oafConfigHolder;
        this.agentRuntime = agentRuntime;
        this.mcpManager = mcpManager;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.skillCatalog = skillCatalog;
        this.internalToolRegistry = internalToolRegistry;
    }

    /**
     * 技能列表：动态数据源（frontmatter 声明 ∪ /config/skills 目录实际内容，
     * 冲突以目录为准），技能目录运行中变化即时可见。
     */
    @GetMapping("/skills")
    public List<Map<String, Object>> listSkills() {
        return skillCatalog.list();
    }

    @GetMapping("/mcp")
    public List<Map<String, Object>> listMcp() {
        return mcpManager.getMcpSummaries(currentMcpConfigs());
    }

    /**
     * 当前 MCP 配置：从磁盘按最新 frontmatter 声明加载（reload 后即时反映）。
     */
    private List<Map<String, Object>> currentMcpConfigs() {
        return mcpManager.loadConfigs(oafConfigHolder.get().mcpServers());
    }

    /**
     * 工具列表：默认只返回 MCP 业务工具，内置工具需显式 includeInternal=true。
     * includeInternal 时输出两段并列的内置视图（issue #39 拆字段）：
     * <ul>
     *   <li>{@code tools} 内的 internal 段——{@link InternalToolRegistry} 的 CustomTool
     *       运行时注册集（issue #28），OAF {@code tools:} 是声明/展示语义而非存在性开关，
     *       declared 字段标注声明意图；registry 经 OafConfigHolder 每请求取值，
     *       OAF reload（deniedTools 变更）即时反映。</li>
     *   <li>{@code sdkInternal} 段——SDK（Harness 框架）注册进 Toolkit 的内置工具
     *       实际注册集（文件/记忆/会话/计划/技能/子 Agent/异步任务/Shell 等），
     *       取自运行中 agent，减去已上报的 MCP/自定义同名项与 deniedTools。</li>
     * </ul>
     * totalCount/internalCount 仍只统计 MCP + CustomTool 段（保持既有口径不破坏消费方），
     * SDK 段规模看 sdkInternalCount。
     */
    @GetMapping("/tools")
    public Map<String, Object> listTools(
        @RequestParam(defaultValue = "false") boolean includeInternal
    ) {
        List<Map<String, Object>> tools = new ArrayList<>();

        // 1. MCP 工具（业务工具，默认暴露）
        List<Map<String, Object>> mcpTools = getMcpTools();
        tools.addAll(mcpTools);

        // 2. 内置工具（可选）：CustomTool 运行时注册集 + declared 标注
        int internalCount = 0;
        List<Map<String, Object>> sdkInternal = List.of();
        if (includeInternal) {
            var internalTools = internalToolRegistry.listInternalTools();
            tools.addAll(internalTools);
            internalCount = internalTools.size();

            // 3. SDK 内置工具（可选，独立段落）：Toolkit 实际注册集减去已上报名
            //    （MCP 与自定义工具也注册进同一 Toolkit 且以裸名登记，需先剔除）
            var reportedNames = new java.util.HashSet<String>();
            mcpTools.forEach(t -> reportedNames.add(String.valueOf(t.get("name"))));
            internalTools.forEach(t -> reportedNames.add(String.valueOf(t.get("name"))));
            sdkInternal = internalToolRegistry.listSdkInternalTools(agentRuntime.getAgent(), reportedNames);
        }

        return Map.of(
            "tools", tools,
            "totalCount", tools.size(),
            "mcpCount", mcpTools.size(),
            "internalCount", internalCount,
            "sdkInternal", sdkInternal,
            "sdkInternalCount", sdkInternal.size()
        );
    }

    private List<Map<String, Object>> getMcpTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var config : currentMcpConfigs()) {
            String serverName = (String) config.getOrDefault("server", "unknown");
            mcpToolRegistrar.getToolsByServer(serverName).stream()
                .map(tool -> {
                    var item = new java.util.LinkedHashMap<String, Object>();
                    item.put("name", tool.name());
                    item.put("server", serverName);
                    item.put("category", "mcp");
                    item.put("description", tool.description() != null ? tool.description() : "");
                    // MCP Apps：UI 元数据 + app_only 标记（前端据此区分展示）
                    if (tool.uiResourceUri() != null) {
                        item.put("uiResourceUri", tool.uiResourceUri());
                    }
                    if (tool.appOnly()) {
                        item.put("appOnly", true);
                    }
                    return item;
                })
                .forEach(result::add);
        }
        return result;
    }
}
