package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpManager;
import io.agentmanager.framework.service.McpToolRegistrar;

@RestController
public class InfoController {

    private final OafConfigHolder oafConfigHolder;
    private final AgentRuntimeService agentRuntime;
    private final McpManager mcpManager;
    private final McpToolRegistrar mcpToolRegistrar;
    private final AgentManagerProperties props;
    /** SDK 协议属性：仅 agent-protocol 启用时由扩展自动配置装配，关闭时为空 */
    private final ObjectProvider<io.agentscope.extensions.agentprotocol.AgentProtocolProperties> agentProtocolProperties;

    public InfoController(
        OafConfigHolder oafConfigHolder,
        AgentRuntimeService agentRuntime,
        McpManager mcpManager,
        McpToolRegistrar mcpToolRegistrar,
        AgentManagerProperties props,
        ObjectProvider<io.agentscope.extensions.agentprotocol.AgentProtocolProperties> agentProtocolProperties
    ) {
        this.oafConfigHolder = oafConfigHolder;
        this.agentRuntime = agentRuntime;
        this.mcpManager = mcpManager;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.props = props;
        this.agentProtocolProperties = agentProtocolProperties;
    }

    /**
     * 当前 MCP 配置：从磁盘按最新 frontmatter 声明加载（reload 后即时反映），
     * 并与注册状态联表出真实 tool_count/has_ui（getMcpSummaries 语义）。
     */
    private List<Map<String, Object>> currentMcpConfigs() {
        return mcpManager.loadConfigs(oafConfigHolder.get().mcpServers());
    }

    /**
     * Agent Protocol（远程子 agent 服务端）状态透出：
     * enabled 以本服务配置（agent.agent-protocol.enabled）为准——它同时决定认证过滤器
     * 与端点是否装配；streaming/hitl 读 SDK 扩展属性（仅启用时存在，关闭时报 false）；
     * task_store 为配置的本地 FS 退化路径（agent_fs bean override 生效时实际不使用）。
     */
    private Map<String, Object> agentProtocolStatus() {
        var protocol = props.agentProtocol();
        var sdk = agentProtocolProperties.getIfAvailable();
        return Map.of(
            "enabled", protocol != null && protocol.enabled(),
            "streaming", sdk != null && sdk.isStreamingEnabled(),
            "hitl", sdk != null && sdk.isHitlEnabled(),
            "task_store", protocol != null ? protocol.taskStore() : ""
        );
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        var oafConfig = oafConfigHolder.get();
        return Map.of(
            "agent", oafConfig.name(),
            "slug", oafConfig.slug(),
            "version", oafConfig.version(),
            "description", oafConfig.description(),
            "protocols", Map.of("a2a", "1.0.0", "a2ui", "v0.8", "oaf", "v0.8.0",
                "agent_protocol", agentProtocolStatus()),
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
        var oafConfig = oafConfigHolder.get();
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
        result.put("mcp", mcpManager.getMcpSummaries(currentMcpConfigs()));
        result.put("protocols", Map.of("a2a", "1.0.0", "a2ui", "v0.8", "oaf", "v0.8.0"));
        result.put("agent_protocol", agentProtocolStatus());

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
            "base_prompt", oafConfigHolder.get().systemPrompt()
        );
    }
}
