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
import io.agentmanager.framework.util.ExternalUrlSupport;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class InfoController {

    private final OafConfigHolder oafConfigHolder;
    private final AgentRuntimeService agentRuntime;
    private final McpManager mcpManager;
    private final McpToolRegistrar mcpToolRegistrar;
    private final AgentManagerProperties props;
    /** SDK 协议属性：仅 agent-protocol 启用时由扩展自动配置装配，关闭时为空 */
    private final ObjectProvider<io.agentscope.extensions.agentprotocol.AgentProtocolProperties> agentProtocolProperties;
    /** A2A Job 配置：独立绑定（Issue #69），enabled 状态供 /metadata 透出 */
    private final ObjectProvider<io.agentmanager.framework.config.AgentA2aJobProperties> a2aJobProperties;
    /** A2A Job 并发准入（仅启用时存在）：availablePermits 可观测 */
    private final ObjectProvider<io.agentmanager.framework.service.a2ajob.A2aJobService> a2aJobService;

    public InfoController(
        OafConfigHolder oafConfigHolder,
        AgentRuntimeService agentRuntime,
        McpManager mcpManager,
        McpToolRegistrar mcpToolRegistrar,
        AgentManagerProperties props,
        ObjectProvider<io.agentscope.extensions.agentprotocol.AgentProtocolProperties> agentProtocolProperties,
        ObjectProvider<io.agentmanager.framework.config.AgentA2aJobProperties> a2aJobProperties,
        ObjectProvider<io.agentmanager.framework.service.a2ajob.A2aJobService> a2aJobService
    ) {
        this.oafConfigHolder = oafConfigHolder;
        this.agentRuntime = agentRuntime;
        this.mcpManager = mcpManager;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.props = props;
        this.agentProtocolProperties = agentProtocolProperties;
        this.a2aJobProperties = a2aJobProperties;
        this.a2aJobService = a2aJobService;
    }

    /**
     * A2A 幂等 Job 状态词表（Issue #69 §2.3，沿 agent_protocol 透出先例）。
     * enabled 恒取本服务配置；maxConcurrent/availablePermits 仅启用时有值。
     */
    private Map<String, Object> a2aJobStatus() {
        var p = a2aJobProperties.getIfAvailable();
        var enabled = p != null && p.enabled();
        var status = new LinkedHashMap<String, Object>();
        status.put("enabled", enabled);
        if (enabled) {
            status.put("maxConcurrent", p.maxConcurrent());
            var svc = a2aJobService.getIfAvailable();
            status.put("availablePermits", svc != null ? svc.availablePermits() : null);
        }
        return status;
    }

    /**
     * 当前 MCP 配置：从磁盘按最新 frontmatter 声明加载（reload 后即时反映），
     * 并与注册状态联表出真实 tool_count/has_ui（getMcpSummaries 语义）。
     */
    private List<Map<String, Object>> currentMcpConfigs() {
        return mcpManager.loadConfigs(oafConfigHolder.get().mcpServers());
    }

    /**
     * Agent Protocol（远程子 agent 服务端）状态词表——单一来源，/.well-known/agent-card.json
     * （AgentCardController）同源复用（travel-fulfillment 设计 §8 member-6）。
     *
     * <p>enabled 以本服务配置（agent.agent-protocol.enabled）为准——它同时决定认证过滤器
     * 与端点是否装配；streaming/hitl 读 SDK 扩展属性（仅启用时存在，关闭时报 false）；
     * task_store 为配置的本地 FS 退化路径（agent_fs bean override 生效时实际不使用）。
     */
    public static Map<String, Object> agentProtocolStatus(
        AgentManagerProperties props,
        ObjectProvider<io.agentscope.extensions.agentprotocol.AgentProtocolProperties> agentProtocolProperties) {
        var protocol = props != null ? props.agentProtocol() : null;
        var sdk = agentProtocolProperties != null ? agentProtocolProperties.getIfAvailable() : null;
        return Map.of(
            "enabled", protocol != null && protocol.enabled(),
            "streaming", sdk != null && sdk.isStreamingEnabled(),
            "hitl", sdk != null && sdk.isHitlEnabled(),
            "task_store", protocol != null ? protocol.taskStore() : ""
        );
    }

    private Map<String, Object> agentProtocolStatus() {
        return agentProtocolStatus(props, agentProtocolProperties);
    }

    /**
     * endpoints 广告词表（subpath-routing-design §3.1）：经代理访问（有外部前缀）时逐项
     * 加前缀，直连时保持根路径——保证按广告拼 URL 的消费者在前缀路由下不 404。
     */
    private static Map<String, String> endpointsMap(String prefix) {
        var paths = Map.of(
            "agent_card", "/.well-known/agent-card.json",
            "jsonrpc", "/",
            "threads", "/threads",
            "health", "/health",
            "debug", "/debug",
            "metadata", "/metadata");
        if (prefix == null) {
            return paths;
        }
        var prefixed = new LinkedHashMap<String, String>();
        paths.forEach((key, path) -> prefixed.put(key, prefix + path));
        return prefixed;
    }

    @GetMapping("/")
    public Map<String, Object> root(HttpServletRequest req) {
        var oafConfig = oafConfigHolder.get();
        var prefix = ExternalUrlSupport.forwardedPrefix(req);
        var result = new LinkedHashMap<String, Object>();
        result.put("agent", oafConfig.name());
        result.put("slug", oafConfig.slug());
        result.put("version", oafConfig.version());
        result.put("description", oafConfig.description());
        result.put("protocols", Map.of("a2a", "1.0.0", "a2ui", "v0.8", "oaf", "v0.8.0",
            "agent_protocol", agentProtocolStatus()));
        result.put("oaf", Map.of(
            "tools", oafConfig.tools(),
            "skills", oafConfig.skills().size(),
            "mcp", oafConfig.mcpServers().size(),
            "sub_agents", oafConfig.subAgents().size()
        ));
        result.put("endpoints", endpointsMap(prefix));
        // additive 字段：仅经代理访问时存在，直连响应体逐字节不变
        if (prefix != null) {
            result.put("base_url", ExternalUrlSupport.externalBase(req));
        }
        result.put("engine", "AgentScope Java 2.0");
        return result;
    }

    /**
     * 完整 Agent 元数据端点：skills 返回对象数组而非数量。
     */
    @GetMapping("/metadata")
    public Map<String, Object> getMetadata(
        @RequestParam(defaultValue = "false") boolean includeDetails,
        HttpServletRequest req
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
        result.put("a2a_job", a2aJobStatus());

        // 详细信息（可选）
        if (includeDetails) {
            result.put("tools", oafConfig.tools());
            result.put("subAgents", oafConfig.subAgents());
            result.put("model", oafConfig.model());
            result.put("endpoints", endpointsMap(ExternalUrlSupport.forwardedPrefix(req)));
            var base = ExternalUrlSupport.externalBase(req);
            if (base != null) {
                result.put("base_url", base);
            }
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
