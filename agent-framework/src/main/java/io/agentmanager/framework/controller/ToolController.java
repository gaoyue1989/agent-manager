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
import io.agentmanager.framework.service.SessionToolScopeService;
import io.agentmanager.framework.service.SkillCatalogService;

@RestController
public class ToolController {

    private final OafConfigHolder oafConfigHolder;
    private final AgentRuntimeService agentRuntime;
    private final McpManager mcpManager;
    private final McpToolRegistrar mcpToolRegistrar;
    private final SkillCatalogService skillCatalog;
    private final InternalToolRegistry internalToolRegistry;
    /** 会话级工具三分类/名单解析（sessionTools 汇总与 ?tools= 过滤同源，session-tool-list-design §5） */
    private final SessionToolScopeService sessionToolScope;

    public ToolController(
        OafConfigHolder oafConfigHolder,
        AgentRuntimeService agentRuntime,
        McpManager mcpManager,
        McpToolRegistrar mcpToolRegistrar,
        SkillCatalogService skillCatalog,
        InternalToolRegistry internalToolRegistry,
        SessionToolScopeService sessionToolScope
    ) {
        this.oafConfigHolder = oafConfigHolder;
        this.agentRuntime = agentRuntime;
        this.mcpManager = mcpManager;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.skillCatalog = skillCatalog;
        this.internalToolRegistry = internalToolRegistry;
        this.sessionToolScope = sessionToolScope;
    }

    /**
     * 技能列表：动态数据源（frontmatter 声明 ∪ /config/skills 目录实际内容，
     * 冲突以目录为准），技能目录运行中变化即时可见。
     *
     * <p>本轮可用技能列表（session-skill-list-design §6）：{@code ?skills=a,b} 可选；
     * 缺省/空串 = 不过滤（全部启用技能）；逗号分隔名单 = 白名单视图（未知名忽略）。
     */
    @GetMapping("/skills")
    public List<Map<String, Object>> listSkills(
            @RequestParam(value = "skills", required = false) String skills) {
        return skillCatalog.list(parseSkillNames(skills));
    }

    /** 逗号分隔名单 → 列表；缺省/空串返回 null（= 不限制，存量语义） */
    static List<String> parseSkillNames(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        var names = new ArrayList<String>();
        for (var part : csv.split(",")) {
            var trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
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
     *
     * <p>会话级工具查询（session-tool-list-design §5，闭环体验）：
     * <ul>
     *   <li>MCP 段条目补 {@code fullName}（{@code mcp__{server}__{tool}}，可传入
     *       chat/A2A tools 字段的全名别名）与 {@code sessionControllable=true}；
     *       internal 段条目 {@code sessionControllable=true}；sdkInternal 段恒 false
     *       （内置不受会话级 tools 控制）。</li>
     *   <li>顶层 {@code sessionTools} 汇总：可传入 tools 字段的清单（MCP 可控集 ∪
     *       自定义集，与 ToolFilterMiddleware 收敛同源 {@link SessionToolScopeService}
     *       ——查询即所得）。</li>
     *   <li>{@code ?tools=a,b}（可选）：白名单预览——只裁剪 MCP 段与自定义段（裸名或
     *       全名均可命中），SDK 内置段不受影响；缺省/空串 = 不过滤（存量语义）。</li>
     * </ul>
     */
    @GetMapping("/tools")
    public Map<String, Object> listTools(
        @RequestParam(defaultValue = "false") boolean includeInternal,
        @RequestParam(required = false) String tools
    ) {
        // ?tools= 白名单解析：经 SessionToolScopeService 与模型侧收敛同口径
        //（裸名/全名归一）；null = 不过滤（缺省/空串，存量语义）
        var requested = parseSkillNames(tools);
        var whitelist = requested != null ? sessionToolScope.resolve(requested).keepNames() : null;

        List<Map<String, Object>> toolEntries = new ArrayList<>();

        // 1. MCP 工具（业务工具，默认暴露）
        List<Map<String, Object>> mcpTools = getMcpTools();
        if (whitelist != null) {
            // app_only 工具不注册 Toolkit、不进白名单解析集——预览恒保留（仅卡片可调
            // 条目不应被业务工具收敛预览吞掉）
            mcpTools = mcpTools.stream()
                .filter(t -> whitelist.contains(String.valueOf(t.get("name")))
                    || Boolean.TRUE.equals(t.get("appOnly")))
                .toList();
        }
        toolEntries.addAll(mcpTools);

        // 2. 内置工具（可选）：CustomTool 运行时注册集 + declared 标注
        int internalCount = 0;
        List<Map<String, Object>> sdkInternal = List.of();
        if (includeInternal) {
            // 拷贝后标注（registry 返回不可变 Map.of 条目）；会话级可控：自定义工具
            // 可传入 chat/A2A tools 字段（裸名）
            var internalTools = new ArrayList<Map<String, Object>>();
            for (var entry : internalToolRegistry.listInternalTools()) {
                var item = new java.util.LinkedHashMap<String, Object>(entry);
                if (whitelist != null && !whitelist.contains(String.valueOf(item.get("name")))) {
                    continue;
                }
                item.put("sessionControllable", true);
                internalTools.add(item);
            }
            toolEntries.addAll(internalTools);
            internalCount = internalTools.size();

            // 3. SDK 内置工具（可选，独立段落）：Toolkit 实际注册集减去已上报名
            //    （MCP 与自定义工具也注册进同一 Toolkit 且以裸名登记，需先剔除）；
            //    不受 ?tools= 白名单影响（内置恒可见，与模型侧同口径）
            var reportedNames = new java.util.HashSet<String>();
            mcpTools.forEach(t -> reportedNames.add(String.valueOf(t.get("name"))));
            internalTools.forEach(t -> reportedNames.add(String.valueOf(t.get("name"))));
            sdkInternal = internalToolRegistry.listSdkInternalTools(agentRuntime.getAgent(), reportedNames)
                .stream()
                .map(t -> {
                    // 内置工具不受会话级 tools 控制（恒可见），显式 false 供前端区分
                    var item = new java.util.LinkedHashMap<String, Object>(t);
                    item.put("sessionControllable", false);
                    return (Map<String, Object>) item;
                })
                .toList();
        }

        // sessionTools 汇总（§5）：可传入 chat/A2A tools 字段的清单，与过滤/收敛同源
        var sessionNames = new java.util.TreeSet<String>();
        sessionNames.addAll(sessionToolScope.mcpToolNames());
        sessionNames.addAll(sessionToolScope.customToolNames());

        return Map.of(
            "tools", toolEntries,
            "totalCount", toolEntries.size(),
            "mcpCount", mcpTools.size(),
            "internalCount", internalCount,
            "sdkInternal", sdkInternal,
            "sdkInternalCount", sdkInternal.size(),
            "sessionTools", Map.of(
                "tools", List.copyOf(sessionNames),
                "count", sessionNames.size(),
                "hint", "Pass these names (bare) in chat/A2A 'tools'; MCP tools also accept "
                    + "full name mcp__{server}__{tool}. Built-in tools are always visible and "
                    + "not session-controllable (disable via package deniedTools)."
            )
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
                    // 全名别名（session-tool-list-design §5）：可传入 chat/A2A tools 字段的
                    // mcp__{server}__{tool} 形态
                    item.put("fullName", tool.displayName());
                    item.put("server", serverName);
                    item.put("category", "mcp");
                    // 会话级 tools 白名单可控（内置恒可见，sdkInternal 段恒 false）；
                    // app_only 工具不注册 Toolkit、不可传入 tools 字段 → false（口径与
                    // sessionTools 清单一致：查询即所得，传了也会被静默忽略）
                    item.put("sessionControllable", !tool.appOnly());
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
