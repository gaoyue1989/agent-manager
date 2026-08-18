package io.agentmanager.framework.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/**
 * AgentScope 原生 MCP 工具注册。
 * 从 OAF mcp-configs/{server}/config.yaml 读取连接配置，
 * 使用 McpClientBuilder 构建并注册到 Toolkit。
 *
 * 工具命名遵循官方规范 mcp__{server}__{tool}，避免跨 server 同名冲突。
 * 支持 ActiveMCP.json 的 selectedTools 子集过滤（enabled:false 的工具不注册）。
 */
@Service
public class McpToolRegistrar {
    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrar.class);

    /** 支持的权限行为值（permissions.tools 声明） */
    private static final Set<String> PERMISSION_BEHAVIORS = Set.of("allow", "ask", "deny");

    private final Path configDir;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 已注册工具缓存: serverName + ":" + toolName -> ToolInfo（key 用原始工具名） */
    private final Map<String, ToolInfo> registeredTools = new ConcurrentHashMap<>();

    public McpToolRegistrar(io.agentmanager.framework.config.AgentManagerProperties props) {
        this.configDir = Path.of(props.configDir());
    }

    /**
     * 注册所有 MCP 服务器到 Toolkit。
     * 支持 config.yaml 的 permissions.read_only 强制只读（服务端未标注 readOnlyHint 时兜底）。
     * 支持 ActiveMCP.json 的 selectedTools 子集过滤。
     *
     * @param toolkit    目标 Toolkit
     * @param oafConfig  OAF 配置
     */
    public void registerAll(Toolkit toolkit, OafConfig oafConfig) {
        for (var mcp : oafConfig.mcpServers()) {
            var wrapper = buildClient(mcp);
            if (wrapper != null) {
                // 加载 ActiveMCP.json 配置（enabled 子集过滤）
                var activeMcpConfig = loadActiveMcpConfig(mcp);
                boolean forceReadOnly = isReadOnlyConfigured(mcp);
                if (forceReadOnly || activeMcpConfig != null) {
                    // 有 ActiveMCP 配置或强制只读时，走手动注册路径（支持过滤）
                    registerReadOnly(toolkit, wrapper, mcp.server(), activeMcpConfig);
                } else {
                    toolkit.registerMcpClient(wrapper).block();
                    // 标准注册：记录已注册工具信息
                    recordRegisteredTools(wrapper, mcp.server());
                }
                log.info("MCP client registered: {} ({})", mcp.server(), wrapper);
            }
        }
    }

    /**
     * 加载 ActiveMCP.json 配置。
     * package-private：便于单元测试。
     *
     * @return toolName -> enabled 的映射；无 ActiveMCP.json 或解析失败返回 null（不限制）
     */
    Map<String, Boolean> loadActiveMcpConfig(OafConfig.McpServerConfig mcp) {
        var mcpDir = resolveMcpDir(mcp);
        var activeMcp = mcpDir.resolve("ActiveMCP.json");
        if (!activeMcp.toFile().exists()) {
            return null; // 无配置，不限制
        }
        try {
            var node = mapper.readTree(activeMcp.toFile());
            var result = new LinkedHashMap<String, Boolean>();
            if (node.has("selectedTools")) {
                for (var toolNode : node.get("selectedTools")) {
                    var name = toolNode.get("name").asText();
                    var enabled = !toolNode.has("enabled") || toolNode.get("enabled").asBoolean(true);
                    result.put(name, enabled);
                }
            }
            log.info("Loaded ActiveMCP.json for {}: {} tools configured", mcp.server(), result.size());
            return result;
        } catch (Exception e) {
            log.warn("Failed to load ActiveMCP.json for {}: {}", mcp.server(), e.getMessage());
            return null;
        }
    }

    /**
     * 解析 MCP 配置目录：优先 configDir，回退 server 名。
     */
    private Path resolveMcpDir(OafConfig.McpServerConfig mcp) {
        var mcpDir = configDir.resolve(
            mcp.configDir() == null || mcp.configDir().isEmpty() ? mcp.server() : mcp.configDir());
        if (!mcpDir.toFile().exists()) {
            mcpDir = configDir.resolve(mcp.server());
        }
        return mcpDir;
    }

    /**
     * 记录标准注册（非强制只读）的 MCP 工具到缓存。
     * package-private：供 collectPermissionRules 聚合规则；也可用于单元测试预置注册结果。
     */
    void recordRegisteredTools(McpClientWrapper wrapper, String serverName) {
        try {
            var tools = wrapper.listTools().block();
            if (tools == null) {
                log.warn("MCP {} listTools returned null, tools not recorded", serverName);
                return;
            }
            for (var tool : tools) {
                var displayName = "mcp__" + serverName + "__" + tool.name();
                var info = new ToolInfo(
                    tool.name(),
                    displayName,
                    tool.description() != null ? tool.description() : "",
                    serverName
                );
                registeredTools.put(serverName + ":" + tool.name(), info);
            }
            log.info("Recorded {} MCP tools for server {}", tools.size(), serverName);
        } catch (Exception e) {
            log.warn("Failed to record MCP tools for {}: {}", serverName, e.getMessage());
        }
    }

    /**
     * 读取 config.yaml 的 permissions.read_only 配置。
     * package-private：不触发连接，便于单元测试。
     */
    @SuppressWarnings("unchecked")
    boolean isReadOnlyConfigured(OafConfig.McpServerConfig mcp) {
        var data = loadConfigYaml(mcp);
        if (data == null) {
            return false;
        }
        var perms = (Map<String, Object>) data.get("permissions");
        return perms != null && Boolean.TRUE.equals(perms.get("read_only"));
    }

    /**
     * 读取 config.yaml 的 permissions.tools 工具级三态权限（裸名 → allow|ask|deny）。
     * package-private：不触发连接，便于单元测试。
     *
     * @return 裸名 → 行为的映射；无 permissions.tools 或加载失败返回空 Map
     */
    @SuppressWarnings("unchecked")
    Map<String, String> loadToolPermissions(OafConfig.McpServerConfig mcp) {
        var data = loadConfigYaml(mcp);
        if (data == null) {
            return Collections.emptyMap();
        }
        var perms = (Map<String, Object>) data.get("permissions");
        if (perms == null) {
            return Collections.emptyMap();
        }
        var rawTools = perms.get("tools");
        if (!(rawTools instanceof Map<?, ?> toolsMap)) {
            return Collections.emptyMap();
        }
        var result = new LinkedHashMap<String, String>();
        for (var entry : toolsMap.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String behavior)) {
                log.warn("MCP {}: invalid permissions.tools entry ignored: {}={}",
                    mcp.server(), entry.getKey(), entry.getValue());
                continue;
            }
            if (!PERMISSION_BEHAVIORS.contains(behavior)) {
                log.warn("MCP {}: unknown permission behavior '{}' for tool '{}' (expected allow|ask|deny), ignored",
                    mcp.server(), behavior, name);
                continue;
            }
            result.put(name, behavior);
        }
        return result;
    }

    /**
     * 聚合全部 MCP server 的工具权限规则 + 已注册 MCP 工具名集合（ALLOW 兜底用）。
     * 权限装配数据源（AgentScopeConfig 阶段一调用）：
     * - mode：来自 frontmatter config.permission.mode（OafConfig.runtimeConfig().permissionMode()），
     *   非法值回退 DEFAULT 并告警
     * - tools：各 server permissions.tools 显式声明（仅保留已注册工具；未注册声明告警忽略）
     * - mcpNames：本次实际注册的 MCP 工具裸名集合（含 ActiveMCP 子集过滤后的结果）
     *
     * @return {mode, tools, mcpNames}
     */
    public PermissionRuleResult collectPermissionRules(OafConfig oafConfig) {
        var mode = resolvePermissionMode(oafConfig);
        var tools = new LinkedHashMap<String, String>();
        for (var mcp : oafConfig.mcpServers()) {
            for (var entry : loadToolPermissions(mcp).entrySet()) {
                var name = entry.getKey();
                if (!registeredTools.containsKey(mcp.server() + ":" + name)) {
                    log.warn("MCP {}: permission declared for '{}' but tool not registered (ActiveMCP filtered or server offline), ignored",
                        mcp.server(), name);
                    continue;
                }
                var prev = tools.put(name, entry.getValue());
                if (prev != null && !prev.equals(entry.getValue())) {
                    log.warn("MCP tool '{}' declared with conflicting behaviors across servers ({} vs {}), using '{}'",
                        name, prev, entry.getValue(), entry.getValue());
                }
            }
        }
        var mcpNames = new LinkedHashSet<String>();
        for (var info : registeredTools.values()) {
            mcpNames.add(info.name());
        }
        return new PermissionRuleResult(mode, tools, mcpNames);
    }

    /** 解析 frontmatter config.permission.mode → PermissionMode；非法值回退 DEFAULT 并告警 */
    private io.agentscope.core.permission.PermissionMode resolvePermissionMode(OafConfig oafConfig) {
        var raw = oafConfig.runtimeConfig().permissionMode();
        try {
            return io.agentscope.core.permission.PermissionMode.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown permission.mode '{}' (expected default|accept_edits|explore|bypass|dont_ask), falling back to DEFAULT", raw);
            return io.agentscope.core.permission.PermissionMode.DEFAULT;
        }
    }

    /** 工具权限聚合结果。 */
    public record PermissionRuleResult(
        io.agentscope.core.permission.PermissionMode mode,
        Map<String, String> tools,
        Set<String> mcpNames
    ) {}

    /**
     * 强制只读注册：服务端未标注 readOnlyHint 时，通过 config.yaml 兜底。
     * 遍历 MCP 工具，手动构造 readOnly=true 的 McpTool 注册到 Toolkit。
     * 支持 ActiveMCP.json 子集过滤：enabled=false 的工具不注册。
     *
     * 注册名使用远端裸名（tool.name()），确保 McpTool.callAsync 正确执行。
     * mcp__{server}__{tool} 前缀名仅用于 registeredTools 缓存和 API 展示。
     *
     * 注：McpTool.getName() 是 final 字段，callAsync 用它转发给远端 MCP server，
     * 无法分离 LLM 暴露名和执行名。跨 server 同名工具冲突通过 serverName 字段区分。
     *
     * @param activeMcpConfig ActiveMCP.json 的 toolName -> enabled 映射；null 表示不限制
     */
    private void registerReadOnly(Toolkit toolkit, McpClientWrapper wrapper, String serverName,
                                  Map<String, Boolean> activeMcpConfig) {
        wrapper.initialize().block();
        var tools = wrapper.listTools().block();
        if (tools == null) {
            log.warn("MCP {} listTools returned null", serverName);
            return;
        }
        for (var tool : tools) {
            // ActiveMCP 过滤：enabled=false 的工具不注册
            if (activeMcpConfig != null) {
                if (activeMcpConfig.containsKey(tool.name()) && !activeMcpConfig.get(tool.name())) {
                    log.info("MCP tool '{}' skipped (ActiveMCP enabled=false)", tool.name());
                    continue;
                }
            }
            // 使用远端裸名注册，确保 callAsync 正确执行
            var agentTool = new io.agentscope.core.tool.mcp.McpTool(
                tool.name(),
                tool.description() != null ? tool.description() : "",
                io.agentscope.core.tool.mcp.McpTool.convertMcpSchemaToParameters(
                    tool.inputSchema(), java.util.Collections.emptySet()),
                tool.outputSchema() != null ? new java.util.concurrent.ConcurrentHashMap<>(tool.outputSchema()) : null,
                wrapper,
                null,
                serverName,
                true // readOnly=true 强制只读
            );
            toolkit.registerTool(agentTool);
            // 缓存 key 用原始工具名，API 展示用 mcp__ 前缀名（跨 server 区分）
            var displayName = "mcp__" + serverName + "__" + tool.name();
            registeredTools.put(serverName + ":" + tool.name(),
                new ToolInfo(tool.name(), displayName, tool.description(), serverName));
            log.info("MCP tool '{}' registered (display: {}, read-only)", tool.name(), displayName);
        }
    }

    /**
     * 从 config.yaml 读取原始 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfigYaml(OafConfig.McpServerConfig mcp) {
        var mcpDir = resolveMcpDir(mcp);
        var configYaml = mcpDir.resolve("config.yaml");
        if (!configYaml.toFile().exists()) {
            return null;
        }
        try {
            var yaml = new Yaml();
            return (Map<String, Object>) yaml.load(Files.newInputStream(configYaml));
        } catch (Exception e) {
            log.error("Failed to load config.yaml for {}: {}", mcp.server(), e.getMessage());
            return null;
        }
    }

    /**
     * 从 config.yaml 构建 McpClientWrapper。
     * 支持 sse / streamableHttp / stdio 三种传输。
     * package-private：构建本身不发起连接，便于单元测试。
     */
    @SuppressWarnings("unchecked")
    McpClientWrapper buildClient(OafConfig.McpServerConfig mcp) {
        var mcpDir = resolveMcpDir(mcp);
        var configYaml = mcpDir.resolve("config.yaml");
        if (!configYaml.toFile().exists()) {
            log.warn("MCP config.yaml not found for {} at {}", mcp.server(), configYaml);
            return null;
        }

        try {
            var yaml = new Yaml();
            var data = (Map<String, Object>) yaml.load(Files.newInputStream(configYaml));
            if (data == null || !data.containsKey("connection")) {
                log.warn("MCP config.yaml for {} missing 'connection' section", mcp.server());
                return null;
            }
            var conn = (Map<String, Object>) data.get("connection");
            var type = (String) conn.getOrDefault("type", "sse");

            var builder = McpClientBuilder.create(mcp.server());

            switch (type) {
                case "stdio" -> {
                    var command = (String) conn.get("command");
                    var args = (List<String>) conn.getOrDefault("args", List.of());
                    builder.stdioTransport(command, args.toArray(new String[0]));
                    log.info("MCP {} stdio: {} {}", mcp.server(), command, args);
                }
                case "streamableHttp", "http" -> {
                    builder.streamableHttpTransport((String) conn.get("url"));
                    log.info("MCP {} streamableHttp: {}", mcp.server(), conn.get("url"));
                }
                default -> { // sse
                    builder.sseTransport((String) conn.get("url"));
                    log.info("MCP {} sse: {}", mcp.server(), conn.get("url"));
                }
            }

            // 认证（可选）
            if (data.containsKey("auth")) {
                var auth = (Map<String, Object>) data.get("auth");
                var token = (String) auth.get("token");
                if (token != null && !token.isBlank()) {
                    builder.header("Authorization", "Bearer " + resolveEnv(token));
                }
            }

            return builder.buildSync();
        } catch (Exception e) {
            log.error("Failed to build MCP client for {}: {}", mcp.server(), e.getMessage());
            return null;
        }
    }

    /** 支持 ${ENV_VAR} 语法。 */
    private String resolveEnv(String value) {
        if (value.startsWith("${") && value.endsWith("}")) {
            var envName = value.substring(2, value.length() - 1);
            var envVal = System.getenv(envName);
            if (envVal != null) {
                return envVal;
            }
            log.warn("Env var {} not set, using empty value", envName);
            return "";
        }
        return value;
    }

    /**
     * 按 server 名称查询已注册的 MCP 工具。
     */
    public List<ToolInfo> getToolsByServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return List.of();
        }
        var prefix = serverName + ":";
        return registeredTools.entrySet().stream()
            .filter(e -> e.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .toList();
    }

    /**
     * 获取所有已注册的 MCP 工具。
     */
    public List<ToolInfo> getAllRegisteredTools() {
        return new ArrayList<>(registeredTools.values());
    }

    /**
     * 已注册 MCP 工具信息。
     *
     * @param name        远端裸名（如 get_weather）
     * @param displayName API 展示名（如 mcp__travel__get_weather）
     * @param description 工具描述
     * @param serverName  MCP 服务器名
     */
    public record ToolInfo(String name, String displayName, String description, String serverName) {}
}
