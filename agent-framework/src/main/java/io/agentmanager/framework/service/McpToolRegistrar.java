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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.mcp.McpUserHeaderCustomizer;
import io.agentmanager.framework.mcp.UserHeaderRule;
import io.agentmanager.framework.mcp.UserScopedMcpClientWrapper;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * AgentScope 原生 MCP 工具注册。
 * 从 OAF mcp-configs/{server}/config.yaml 读取连接配置，
 * 使用 McpClientBuilder 构建并注册到 Toolkit。
 *
 * 工具命名遵循官方规范 mcp__{server}__{tool}，避免跨 server 同名冲突。
 * 支持 ActiveMCP.json 的 selectedTools 子集过滤（enabled:false 的工具不注册）。
 *
 * MCP Apps 扩展（阶段一）：
 * - config.yaml ui 段静态声明（ui.tools / ui.app_only / ui.csp）
 * - Tool._meta.ui.resourceUri 自动发现兜底（0.17.0 Tool.meta() 可读）
 * - app_only 工具不注册 Toolkit，但记录 ToolInfo（供代理校验 + /tools 标记）
 * - buildSyncClient() 构建独立 McpSyncClient 供 McpResourceProxy 使用
 */
@Service
public class McpToolRegistrar {
    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrar.class);

    /** 支持的权限行为值（permissions.tools 声明） */
    private static final Set<String> PERMISSION_BEHAVIORS = Set.of("allow", "ask", "deny");

    /** reload 路径对 MCP 网络 IO（listTools/关闭连接）的单步阻塞上限（秒），防 server 挂起占住 reload 监视器 */
    static final long RELOAD_BLOCK_TIMEOUT_SECONDS = 10;

    /** ui:// 资源 scheme 前缀 */
    public static final String UI_SCHEME = "ui://";

    private final Path configDir;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 已注册工具缓存: serverName + ":" + toolName -> ToolInfo（key 用原始工具名） */
    private final Map<String, ToolInfo> registeredTools = new ConcurrentHashMap<>();

    /** UI 映射缓存: serverName -> UiMapping（config.yaml ui 段解析结果） */
    private final Map<String, UiMapping> uiMappings = new ConcurrentHashMap<>();

    /** 工具权限行为缓存: serverName -> (toolName -> allow|ask|deny)（registerAll 时装载） */
    private final Map<String, Map<String, String>> toolPermissions = new ConcurrentHashMap<>();

    /** 只读 server 缓存: serverName -> read_only（registerAll 时装载） */
    private final Map<String, Boolean> readOnlyServers = new ConcurrentHashMap<>();
    /** 启动严格模式缓存: serverName -> required（config.yaml startup.required，默认 false=容错跳过） */
    private final Map<String, Boolean> startupRequired = new ConcurrentHashMap<>();

    /** destructiveHint 缓存: serverName:toolName -> destructiveHint（buildToolInfo 时记录） */
    private final Map<String, Boolean> destructiveHints = new ConcurrentHashMap<>();

    /** 已注册连接跟踪: serverName -> McpClientWrapper（registerOne 成功后记录，reload 关闭旧连接用） */
    private final Map<String, McpClientWrapper> registeredWrappers = new ConcurrentHashMap<>();

    public McpToolRegistrar(io.agentmanager.framework.config.AgentManagerProperties props) {
        this.configDir = Path.of(props.resolvedConfigDir());
    }

    /**
     * 注册所有 MCP 服务器到 Toolkit。
     * 支持 config.yaml 的 permissions.read_only 强制只读（服务端未标注 readOnlyHint 时兜底）。
     * 支持 ActiveMCP.json 的 selectedTools 子集过滤。
     * 支持 config.yaml ui.app_only 声明（app_only 工具不注册，对 LLM 隐藏）。
     *
     * @param toolkit    目标 Toolkit
     * @param oafConfig  OAF 配置
     */
    public void registerAll(Toolkit toolkit, OafConfig oafConfig) {
        var servers = oafConfig.mcpServers();
        log.info("[MCP] starting registration: {} servers configured", servers.size());
        for (var mcp : servers) {
            registerOne(toolkit, mcp);
        }
    }

    /**
     * 注册单个 MCP server 到 Toolkit（registerAll 循环体提取，供运行时 reload 复用）。
     * 语义与启动期注册完全一致：ui/权限/只读/required 配置即时装载，
     * fail-soft（连接失败仅告警跳过），startup.required=true 时抛异常。
     *
     * @return 本次注册的 server 名（构建失败返回 null）
     */
    /**
     * reload 两阶段阶段一：仅构建客户端（不触碰任何注册状态，供 swap-on-success 先建后摘）。
     * 构建失败返回 null（config.yaml 缺失/格式错误）。
     */
    public McpClientWrapper buildClientForReload(OafConfig.McpServerConfig mcp) {
        return buildClient(mcp);
    }

    /**
     * reload 两阶段阶段二：登记已构建的客户端（配置装载 + 双路径注册 + 跟踪）。
     * 与 {@link #registerOne} 共享注册语义（含 startup.required 严格失败）。
     *
     * @return server 名
     */
    public String registerBuiltClient(Toolkit toolkit, McpClientWrapper wrapper, OafConfig.McpServerConfig mcp) {
        var uiMapping = loadUiMapping(mcp);
        uiMappings.put(mcp.server(), uiMapping);
        toolPermissions.put(mcp.server(), loadToolPermissions(mcp));
        readOnlyServers.put(mcp.server(), isReadOnlyConfigured(mcp));
        startupRequired.put(mcp.server(), isStartupRequired(mcp));
        try {
            var activeMcpConfig = loadActiveMcpConfig(mcp);
            boolean forceReadOnly = Boolean.TRUE.equals(readOnlyServers.get(mcp.server()));
            boolean hasAppOnly = !uiMapping.appOnly().isEmpty();
            if (forceReadOnly || activeMcpConfig != null || hasAppOnly) {
                registerFiltered(toolkit, wrapper, mcp.server(), activeMcpConfig, uiMapping, forceReadOnly);
            } else {
                toolkit.registerMcpClient(wrapper).block(java.time.Duration.ofSeconds(RELOAD_BLOCK_TIMEOUT_SECONDS));
                recordRegisteredTools(wrapper, mcp.server(), uiMapping);
            }
            registeredWrappers.put(mcp.server(), wrapper);
            log.info("MCP client registered: {} ({})", mcp.server(), wrapper);
            return mcp.server();
        } catch (Exception e) {
            closeQuietly(wrapper);
            if (Boolean.TRUE.equals(startupRequired.get(mcp.server()))) {
                throw new IllegalStateException(
                    "MCP server '" + mcp.server() + "' declared startup.required=true but registration failed",
                    e);
            }
            log.warn("[MCP] server '{}' registration failed, skipped: {}", mcp.server(), e.getMessage());
            return null;
        }
    }

    /** reload 兜底：显式关闭已跟踪的 wrapper（clearServer 已 remove 跟踪，按引用直接关）。 */
    public void closeWrapperQuietly(McpClientWrapper wrapper) {
        closeQuietly(wrapper);
    }

    /** reload 兜底：按 server 名关闭并移除跟踪的 wrapper（旧 agent 收尾用，未跟踪时 no-op）。 */
    public void closeWrapperQuietlyByName(String serverName) {
        var w = registeredWrappers.remove(serverName);
        if (w != null) {
            closeQuietly(w);
        }
    }

    public String registerOne(Toolkit toolkit, OafConfig.McpServerConfig mcp) {
        var uiMapping = loadUiMapping(mcp);
        uiMappings.put(mcp.server(), uiMapping);
        toolPermissions.put(mcp.server(), loadToolPermissions(mcp));
        readOnlyServers.put(mcp.server(), isReadOnlyConfigured(mcp));
        startupRequired.put(mcp.server(), isStartupRequired(mcp));
        var wrapper = buildClient(mcp);
        if (wrapper == null) {
            log.warn("[MCP] skipped server '{}': client build failed", mcp.server());
            return null;
        }
        // MCP 是外部依赖：默认 fail-soft（连接失败仅告警并跳过该 server 的工具，
        // 不阻断 agent 启动）；config.yaml 声明 startup.required=true 时才严格失败
        try {
            // 加载 ActiveMCP.json 配置（enabled 子集过滤）
            var activeMcpConfig = loadActiveMcpConfig(mcp);
            boolean forceReadOnly = Boolean.TRUE.equals(readOnlyServers.get(mcp.server()));
            boolean hasAppOnly = !uiMapping.appOnly().isEmpty();
            if (forceReadOnly || activeMcpConfig != null || hasAppOnly) {
                // 手动注册路径（支持 app_only/子集过滤）。readOnlyHint 只跟随 permissions.read_only，
                // 否则会短路 ask 权限（D6）。
                registerFiltered(toolkit, wrapper, mcp.server(), activeMcpConfig, uiMapping, forceReadOnly);
            } else {
                toolkit.registerMcpClient(wrapper).block();
                // 标准注册：记录已注册工具信息
                recordRegisteredTools(wrapper, mcp.server(), uiMapping);
            }
            var prev = registeredWrappers.put(mcp.server(), wrapper);
            if (prev != null && prev != wrapper) {
                // 重注册路径（reload）：旧连接已由调用方 removeMcpClient 关闭，此处兜底防泄漏
                closeQuietly(prev);
            }
            log.info("MCP client registered: {} ({})", mcp.server(), wrapper);
            return mcp.server();
        } catch (Exception e) {
            closeQuietly(wrapper);
            if (Boolean.TRUE.equals(startupRequired.get(mcp.server()))) {
                log.error("[MCP] server '{}' declared startup.required=true but registration failed", mcp.server(), e);
                throw new IllegalStateException(
                    "MCP server '" + mcp.server() + "' declared startup.required=true but registration failed",
                    e);
            }
            log.warn("[MCP] server '{}' unreachable at startup, skipped (agent starts without its tools): {}",
                mcp.server(), e.getMessage());
            return null;
        }
    }

    /**
     * 从 Toolkit 与全部 server 级缓存中摘除指定 server（reload 前置步骤）。
     *
     * <p>SDK 2.0.3 语义（字节码核对）：toolkit.removeMcpClient 只覆盖标准路径注册的连接
     * （McpClientManager.mcpClients 命中才摘工具+close）；registerFiltered 手动路径注册的
     * 裸名 McpTool 不在其登记里，且 registerAgentTool 的 mcpClientName=null 永不匹配其过滤条件。
     * 因此本方法对两条路径统一兜底：按 registeredTools 中该 server 的裸名逐个
     * toolkit.removeTool（幂等），并显式关闭被跟踪的旧 wrapper（防连接/stdio 子进程泄漏）。
     *
     * @return true 表示该 server 此前已注册
     */
    public boolean clearServer(Toolkit toolkit, String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return false;
        }
        var oldWrapper = registeredWrappers.remove(serverName);
        var had = oldWrapper != null;
        // ① 手动路径兜底：按裸名逐个摘除（标准路径的工具也是裸名，removeTool 幂等）
        var bareNames = registeredTools.entrySet().stream()
            .filter(e -> e.getKey().startsWith(serverName + ":"))
            .map(e -> e.getValue().name())
            .toList();
        if (toolkit != null) {
            for (var bareName : bareNames) {
                try {
                    toolkit.removeTool(bareName);
                } catch (Exception e) {
                    log.warn("[MCP] removeTool '{}' failed (continuing cleanup): {}", bareName, e.getMessage());
                }
            }
        }
        // ② 标准路径：SDK 摘工具并关闭其登记的连接（手动路径未登记，无效果但不报错）
        if (toolkit != null) {
            try {
                toolkit.removeMcpClient(serverName).block(java.time.Duration.ofSeconds(RELOAD_BLOCK_TIMEOUT_SECONDS));
            } catch (Exception e) {
                log.warn("[MCP] removeMcpClient '{}' failed (continuing cleanup): {}", serverName, e.getMessage());
            }
        }
        // ③ 显式关闭被跟踪的旧 wrapper（两条路径统一，兜 SDK 未覆盖的手动路径连接）
        if (oldWrapper != null) {
            closeQuietly(oldWrapper);
        }
        registeredTools.keySet().removeIf(key -> key.startsWith(serverName + ":"));
        destructiveHints.keySet().removeIf(key -> key.startsWith(serverName + ":"));
        uiMappings.remove(serverName);
        toolPermissions.remove(serverName);
        readOnlyServers.remove(serverName);
        startupRequired.remove(serverName);
        return had;
    }

    /** 解析 config.yaml 的 startup.required（缺省 false=容错） */
    boolean isStartupRequired(OafConfig.McpServerConfig mcp) {
        var data = loadConfigYaml(mcp);
        if (data == null) {
            return false;
        }
        var startup = data.get("startup");
        return startup instanceof Map<?, ?> sm && Boolean.TRUE.equals(sm.get("required"));
    }

    private static void closeQuietly(io.agentscope.core.tool.mcp.McpClientWrapper wrapper) {
        try {
            wrapper.close();
        } catch (Exception ignore) {
            // 尽力而为
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
        recordRegisteredTools(wrapper, serverName, UiMapping.empty());
    }

    /**
     * 记录标准注册（非强制只读）的 MCP 工具到缓存，并应用 UI 映射（config 声明优先，自动发现兜底）。
     */
    void recordRegisteredTools(McpClientWrapper wrapper, String serverName, UiMapping uiMapping) {
        try {
            var tools = wrapper.listTools().block();
            if (tools == null) {
                log.warn("MCP {} listTools returned null, tools not recorded", serverName);
                return;
            }
            for (var tool : tools) {
                var info = buildToolInfo(serverName, tool, uiMapping);
                registeredTools.put(serverName + ":" + tool.name(), info);
            }
            // app_only 工具不注册 Toolkit（对 LLM 隐藏），但必须记录 ToolInfo（供代理校验 + /tools 标记）
            recordAppOnlyTools(serverName, uiMapping, tools);
            log.info("Recorded {} MCP tools for server {}", tools.size(), serverName);
        } catch (Exception e) {
            log.warn("Failed to record MCP tools for {}: {}", serverName, e.getMessage());
        }
    }

    /**
     * 记录 app_only 工具（config.yaml ui.app_only 声明）：
     * 不注册进 Toolkit，但记录 ToolInfo（含 uiResourceUri），供代理校验 + /tools 标记。
     * 若工具同时出现在 server listTools 中（如 visibility:["app"] 仍列出的），以声明为准覆盖。
     */
    private void recordAppOnlyTools(String serverName, UiMapping uiMapping, List<McpSchema.Tool> serverTools) {
        for (var entry : uiMapping.appOnly().entrySet()) {
            var toolName = entry.getKey();
            var uri = entry.getValue();
            var serverTool = serverTools.stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst().orElse(null);
            var description = serverTool != null && serverTool.description() != null
                ? serverTool.description() : "";
            if (serverTool != null) {
                destructiveHints.put(serverName + ":" + toolName,
                    serverTool.annotations() != null && Boolean.TRUE.equals(serverTool.annotations().destructiveHint()));
            }
            var info = new ToolInfo(toolName, "mcp__" + serverName + "__" + toolName,
                description, serverName, uri, "config", true);
            registeredTools.put(serverName + ":" + toolName, info);
            log.info("MCP app-only tool '{}' recorded (display: {}, not registered to Toolkit)",
                toolName, info.displayName());
        }
    }

    /**
     * 构造 ToolInfo：config.yaml ui 声明优先，其次 Tool._meta.ui.resourceUri 自动发现（0.17.0 meta() 可读）。
     * app_only 由调用方决定（普通工具注册路径不可能是 app_only）。
     */
    private ToolInfo buildToolInfo(String serverName, McpSchema.Tool tool, UiMapping uiMapping) {
        var displayName = "mcp__" + serverName + "__" + tool.name();
        var description = tool.description() != null ? tool.description() : "";
        // 记录 destructiveHint（read_only server 的 UI 调用拒绝写工具用）
        destructiveHints.put(serverName + ":" + tool.name(),
            tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().destructiveHint()));
        var declared = uiMapping.tools().get(tool.name());
        if (declared != null) {
            return new ToolInfo(tool.name(), displayName, description, serverName, declared, "config", false);
        }
        var discovered = discoverUiResourceUri(tool);
        if (discovered != null) {
            log.info("MCP tool '{}' ui auto-discovered from _meta.ui: {}", tool.name(), discovered);
            return new ToolInfo(tool.name(), displayName, description, serverName, discovered, "auto", false);
        }
        return new ToolInfo(tool.name(), displayName, description, serverName);
    }

    /**
     * 从 Tool._meta.ui.resourceUri 自动发现 UI 资源（0.17.0 Tool.meta() 可读 _meta）。
     *
     * @return ui:// 资源 URI；无 UI 元数据或格式不符返回 null
     */
    public static String discoverUiResourceUri(McpSchema.Tool tool) {
        if (tool == null || tool.meta() == null) {
            return null;
        }
        Object ui = tool.meta().get("ui");
        if (!(ui instanceof Map<?, ?> uiMap)) {
            return null;
        }
        Object resourceUri = uiMap.get("resourceUri");
        if (!(resourceUri instanceof String s) || !s.startsWith(UI_SCHEME)) {
            return null;
        }
        return s;
    }

    /**
     * 解析 config.yaml 的 ui 段（MCP Apps 静态声明）。
     * package-private：不触发连接，便于单元测试。
     *
     * 格式：
     * ui:
     *   tools: { toolName: "ui://server/mcp-app.html", ... }   # 有 UI 的普通工具
     *   app_only: { toolName: "ui://server/mcp-app.html", ... } # 仅卡片可调，对 LLM 隐藏
     *   csp:
     *     connect_domains: ["https://api.example.com"]
     *     resource_domains: ["https://cdn.example.com"]
     *
     * @return 解析结果；无 ui 段或加载失败返回 UiMapping.empty()
     */
    @SuppressWarnings("unchecked")
    public UiMapping loadUiMapping(OafConfig.McpServerConfig mcp) {
        var data = loadConfigYaml(mcp);
        if (data == null) {
            return UiMapping.empty();
        }
        var rawUi = data.get("ui");
        if (!(rawUi instanceof Map<?, ?> uiMap)) {
            return UiMapping.empty();
        }
        var tools = parseUiToolMap(mcp, uiMap.get("tools"), "ui.tools");
        var appOnly = parseUiToolMap(mcp, uiMap.get("app_only"), "ui.app_only");
        var csp = parseCsp(mcp, uiMap.get("csp"));
        log.info("MCP {} ui config loaded: {} declared, {} app-only, csp={}",
            mcp.server(), tools.size(), appOnly.size(), csp);
        return new UiMapping(tools, appOnly, csp);
    }

    /** 解析 ui.tools / ui.app_only 的 工具名 -> ui:// URI 映射；格式非法项告警跳过 */
    private Map<String, String> parseUiToolMap(OafConfig.McpServerConfig mcp, Object raw, String section) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, String>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String uri)) {
                log.warn("MCP {}: invalid {} entry ignored: {}={}", mcp.server(), section, entry.getKey(), entry.getValue());
                continue;
            }
            if (!uri.startsWith(UI_SCHEME)) {
                log.warn("MCP {}: {} entry '{}' must use ui:// scheme, ignored: {}", mcp.server(), section, name, uri);
                continue;
            }
            result.put(name, uri);
        }
        return result;
    }

    /** 解析 ui.csp 白名单（connect_domains / resource_domains） */
    private UiCsp parseCsp(OafConfig.McpServerConfig mcp, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return UiCsp.empty();
        }
        var connectDomains = parseDomainList(mcp, map.get("connect_domains"), "ui.csp.connect_domains");
        var resourceDomains = parseDomainList(mcp, map.get("resource_domains"), "ui.csp.resource_domains");
        return new UiCsp(connectDomains, resourceDomains);
    }

    private List<String> parseDomainList(OafConfig.McpServerConfig mcp, Object raw, String section) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        var result = new ArrayList<String>();
        for (var item : list) {
            if (item instanceof String s && !s.isBlank()) {
                result.add(s);
            } else {
                log.warn("MCP {}: invalid {} entry ignored: {}", mcp.server(), section, item);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 按名称查询已注册连接（reload 判断 server 是否在线用）。
     */
    public McpClientWrapper getRegisteredWrapper(String serverName) {
        return registeredWrappers.get(serverName);
    }

    /** 当前已注册（含 fail-soft 记录）的 server 名集合。 */
    public java.util.Set<String> getRegisteredServerNames() {
        return java.util.Set.copyOf(registeredWrappers.keySet());
    }

    /**
     * 查询某 server 的 UI 映射（registerAll 时已缓存）。
     */
    public UiMapping getUiMapping(String serverName) {
        return uiMappings.getOrDefault(serverName, UiMapping.empty());
    }

    /** 查询某 server 是否 permissions.read_only（registerAll 时缓存；未缓存时回读 config.yaml） */
    public boolean isServerReadOnly(String serverName) {
        var cached = readOnlyServers.get(serverName);
        if (cached != null) {
            return cached;
        }
        // 未缓存（如单测直接调用）：按 server 名回读 config.yaml
        return isReadOnlyConfigured(new OafConfig.McpServerConfig("", serverName, "", serverName, true));
    }

    /** 查询某 server 工具的权限行为（allow|ask|deny）；未声明返回 allow */
    public String getToolPermission(String serverName, String toolName) {
        return toolPermissions.getOrDefault(serverName, Map.of())
            .getOrDefault(toolName, "allow");
    }

    /** 查询某 server 工具是否 destructiveHint（proxy 拒绝写工具用）；未记录返回 false */
    public boolean isDestructiveHint(String serverName, String toolName) {
        return Boolean.TRUE.equals(destructiveHints.get(serverName + ":" + toolName));
    }

    /** 是否 app_only 工具（/tools 标记与代理校验用） */
    public boolean isAppOnly(String serverName, String toolName) {
        return getToolsByServer(serverName).stream()
            .anyMatch(t -> t.name().equals(toolName) && t.appOnly());
    }

    /**
     * 查询某 server 全部已声明/发现的 ui:// 资源 URI 集合（代理校验 uri ∈ 声明集合用）。
     */
    public Set<String> getUiResourceUris(String serverName) {
        return getToolsByServer(serverName).stream()
            .map(ToolInfo::uiResourceUri)
            .filter(uri -> uri != null && !uri.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 按裸名查询 ui:// 资源 URI（SSE 词表扩展用）。
     * 跨 server 同名工具（裸名相同）时：多个 ToolInfo 的 uiResourceUri 相同 → 可正常携带；
     * 不同 → 不携带 ui 字段（安全降级为普通工具行，并记 WARN 提示管理员消歧）。
     *
     * @return {resourceUri, serverName}；无 UI 或歧义冲突返回 null
     */
    public UiRef resolveUiRef(String toolName) {
        var matches = registeredTools.values().stream()
            .filter(i -> i.name().equals(toolName) && i.uiResourceUri() != null)
            .toList();
        if (matches.isEmpty()) {
            return null;
        }
        var distinctUris = matches.stream().map(ToolInfo::uiResourceUri).distinct().toList();
        if (distinctUris.size() > 1) {
            log.warn("MCP tool '{}' has conflicting uiResourceUri across servers ({}), ui metadata omitted",
                toolName, distinctUris);
            return null;
        }
        return new UiRef(distinctUris.get(0), matches.get(0).serverName());
    }

    /** 裸名 → UI 资源引用（SSE 词表扩展用） */
    public record UiRef(String resourceUri, String serverName) {}

    /**
     * 按裸名查询已注册工具（app_only 工具亦在内）。
     * 跨 server 同名工具时返回第一个（调用方用 server 限定场景不受影响）。
     */
    public ToolInfo getToolByName(String toolName) {
        return registeredTools.values().stream()
            .filter(i -> i.name().equals(toolName))
            .findFirst().orElse(null);
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
     * 支持 ui.app_only 声明：app_only 工具不注册（对 LLM 隐藏），仅记录 ToolInfo。
     *
     * 注册名使用远端裸名（tool.name()），确保 McpTool.callAsync 正确执行。
     * mcp__{server}__{tool} 前缀名仅用于 registeredTools 缓存和 API 展示。
     *
     * 注：McpTool.getName() 是 final 字段，callAsync 用它转发给远端 MCP server，
     * 无法分离 LLM 暴露名和执行名。跨 server 同名工具冲突通过 serverName 字段区分。
     *
     * @param activeMcpConfig ActiveMCP.json 的 toolName -> enabled 映射；null 表示不限制
     * @param uiMapping       config.yaml ui 段解析结果（app_only 过滤 + UI 元数据）
     */
    private void registerReadOnly(Toolkit toolkit, McpClientWrapper wrapper, String serverName,
                                  Map<String, Boolean> activeMcpConfig, UiMapping uiMapping) {
        registerFiltered(toolkit, wrapper, serverName, activeMcpConfig, uiMapping, true);
    }

    /**
     * 手动注册路径（支持 app_only 过滤 + ActiveMCP 子集过滤）。
     *
     * <p><b>readOnly 语义必须与触发原因一致</b>（2026-09-19 修复，e2e-ci-plan §11.3 D6）：
     * 此前 app_only / ActiveMCP 声明也会走本路径并强制 {@code readOnly=true}，而只读工具在
     * SDK 侧绕过权限系统（HITL ask 被短路，confirm 卡不再出现）。现由调用方显式传入
     * {@code readOnlyHint}：只有 config.yaml 的 {@code permissions.read_only} 才是真只读，
     * app_only 与子集过滤只是"从 LLM 工具集里摘掉某些工具"，不影响其余工具的权限评估。
     */
    private void registerFiltered(Toolkit toolkit, McpClientWrapper wrapper, String serverName,
                                  Map<String, Boolean> activeMcpConfig, UiMapping uiMapping,
                                  boolean readOnlyHint) {
        wrapper.initialize().block();
        var tools = wrapper.listTools().block();
        if (tools == null) {
            log.warn("MCP {} listTools returned null", serverName);
            return;
        }
        for (var tool : tools) {
            // app_only 工具：不注册 Toolkit，仅记录 ToolInfo（对 LLM 隐藏）
            if (uiMapping.appOnly().containsKey(tool.name())) {
                log.info("MCP tool '{}' skipped (app_only, hidden from LLM)", tool.name());
                continue;
            }
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
                readOnlyHint
            );
            toolkit.registerTool(agentTool);
            // 缓存 key 用原始工具名，API 展示用 mcp__ 前缀名（跨 server 区分）
            registeredTools.put(serverName + ":" + tool.name(), buildToolInfo(serverName, tool, uiMapping));
            log.info("MCP tool '{}' registered (display: {}, {})", tool.name(),
                "mcp__" + serverName + "__" + tool.name(), readOnlyHint ? "read-only" : "permission-evaluated");
        }
        // app_only 工具记录 ToolInfo（供代理校验 + /tools 标记）
        recordAppOnlyTools(serverName, uiMapping, tools);
    }

    /** 测试桥接：暴露 private registerReadOnly 供单元测试验证 app_only 过滤与 destructiveHint 记录 */
    void registerReadOnlyForTest(Toolkit toolkit, McpClientWrapper wrapper, String serverName,
                                 Map<String, Boolean> activeMcpConfig, UiMapping uiMapping) {
        registerReadOnly(toolkit, wrapper, serverName, activeMcpConfig, uiMapping);
    }

    /** 测试桥接：按显式 readOnlyHint 走手动注册路径（验证 D6：app_only 不应强制只读） */
    void registerFilteredForTest(Toolkit toolkit, McpClientWrapper wrapper, String serverName,
                                 Map<String, Boolean> activeMcpConfig, UiMapping uiMapping,
                                 boolean readOnlyHint) {
        registerFiltered(toolkit, wrapper, serverName, activeMcpConfig, uiMapping, readOnlyHint);
    }

    /** 测试桥接：以桩 wrapper 直接置注册跟踪（模拟 registerOne 成功后的缓存状态）。 */
    void registerOneForTest(Toolkit toolkit, OafConfig.McpServerConfig mcp) {
        uiMappings.put(mcp.server(), loadUiMapping(mcp));
        toolPermissions.put(mcp.server(), loadToolPermissions(mcp));
        registeredWrappers.put(mcp.server(), dummyWrapper(mcp.server()));
    }

    /** 测试桩 wrapper：不发起任何连接，仅供注册跟踪/清理逻辑验证。 */
    private static McpClientWrapper dummyWrapper(String serverName) {
        return new McpClientWrapper(serverName) {
            @Override
            public reactor.core.publisher.Mono<Void> initialize() {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Mono<List<io.modelcontextprotocol.spec.McpSchema.Tool>> listTools() {
                return reactor.core.publisher.Mono.just(List.of());
            }

            @Override
            public reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> callTool(
                    String name, Map<String, Object> arguments) {
                return reactor.core.publisher.Mono.error(new UnsupportedOperationException("stub"));
            }

            @Override
            public reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> callTool(
                    String name, Map<String, Object> arguments, Map<String, Object> meta) {
                return reactor.core.publisher.Mono.error(new UnsupportedOperationException("stub"));
            }

            @Override
            public void close() {
                // stub 无资源
            }
        };
    }

    /** 测试桥接：预置工具权限行为缓存（模拟 registerAll 装载） */
    void setToolPermissionsForTest(String serverName, Map<String, String> perms) {
        toolPermissions.put(serverName, perms);
    }

    /** 测试桥接：预置只读 server 缓存（模拟 registerAll 装载） */
    void setReadOnlyForTest(String serverName, boolean readOnly) {
        readOnlyServers.put(serverName, readOnly);
    }

    /** 测试桥接：标记 destructiveHint（模拟 buildToolInfo 记录） */
    void markDestructiveHintForTest(String serverName, String toolName) {
        destructiveHints.put(serverName + ":" + toolName, true);
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

            // 用户级 header 注入（userHeaders 节）：per-call 按调用方 McpMeta 映射覆盖
            UserHeaderRule userHeaderRule = null;
            if ("stdio".equals(type)) {
                if (data.containsKey("userHeaders")) {
                    log.warn("MCP {} declares userHeaders but uses 'stdio' transport; "
                        + "user-scoped headers only apply to HTTP transports - ignored", mcp.server());
                }
            } else {
                userHeaderRule = parseUserHeaders(data, mcp.server());
                if (userHeaderRule != null) {
                    builder.httpRequestCustomizer(McpUserHeaderCustomizer.userHeader());
                }
            }

            // buildAsync：per-call header 依赖 Reactor Context 传播（sync 客户端 block() 桥接会断链）
            var wrapper = builder.buildAsync().block();
            if (wrapper == null) {
                log.warn("MCP client build returned empty for {}", mcp.server());
                return null;
            }
            if (userHeaderRule == null) {
                return wrapper;
            }
            log.info("MCP {} user-scoped headers enabled: {} header(s), on-missing={}",
                mcp.server(), userHeaderRule.headers().size(),
                userHeaderRule.denyOnMissing() ? "deny" : "passthrough");
            return new UserScopedMcpClientWrapper(wrapper, userHeaderRule);
        } catch (Exception e) {
            log.error("Failed to build MCP client for {}: {}", mcp.server(), e.getMessage());
            return null;
        }
    }

    /**
     * 解析 config.yaml 的 userHeaders 节。
     *
     * 格式：
     * userHeaders:
     *   headers: { X-User-Id: userId, ... }   # header 名 → McpMeta entries key
     *   on-missing: deny | passthrough        # 缺省 deny（fail-closed）
     *
     * package-private：不发起连接，便于单元测试。
     *
     * @return 解析结果；无 userHeaders 节 / headers 为空 / 全部条目非法时返回 null
     */
    @SuppressWarnings("unchecked")
    UserHeaderRule parseUserHeaders(Map<String, Object> data, String serverName) {
        if (!(data.get("userHeaders") instanceof Map<?, ?> uh)) {
            return null;
        }
        if (!(uh.get("headers") instanceof Map<?, ?> rawHeaders)) {
            log.warn("MCP {}: userHeaders.headers missing or not a map, ignored", serverName);
            return null;
        }
        var headers = new LinkedHashMap<String, String>();
        for (var entry : rawHeaders.entrySet()) {
            if (entry.getKey() instanceof String header && !header.isBlank()
                && entry.getValue() instanceof String metaKey && !metaKey.isBlank()) {
                headers.put(header, metaKey);
            } else {
                log.warn("MCP {}: invalid userHeaders.headers entry ignored: {}={}",
                    serverName, entry.getKey(), entry.getValue());
            }
        }
        if (headers.isEmpty()) {
            return null;
        }
        var onMissing = uh.get("on-missing");
        boolean denyOnMissing = true; // 缺省 deny（fail-closed）
        if (onMissing instanceof String s) {
            denyOnMissing = !"passthrough".equals(s);
            if (denyOnMissing && !"deny".equals(s)) {
                log.warn("MCP {}: unknown userHeaders.on-missing '{}', fallback to deny", serverName, s);
            }
        } else if (onMissing != null) {
            log.warn("MCP {}: invalid userHeaders.on-missing '{}', fallback to deny", serverName, onMissing);
        }
        return new UserHeaderRule(Map.copyOf(headers), denyOnMissing);
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
     * 构建独立连接的 SDK 原生 McpSyncClient（供 McpResourceProxy 资源代理使用）。
     * 与 buildClient（agentscope McpClientBuilder）独立，同一 config.yaml 连接配置。
     * 0.17.0 构建方式：McpClient.sync(transport).build()（无 McpSyncClient.builder()）。
     * package-private：不发起连接（懒连接由调用方控制），便于单元测试。
     *
     * @return 未配置/构建失败返回 null
     */
    @SuppressWarnings("unchecked")
    McpSyncClient buildSyncClient(OafConfig.McpServerConfig mcp) {
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

            // 认证（可选）：Authorization header 注入（仅在 HTTP 类 transport 生效，stdio 不支持 header）
            final var authHeader = extractAuthHeader(data);

            // 构建 transport（支持与 buildClient 相同的三种传输）
            io.modelcontextprotocol.spec.McpClientTransport transport;
            if ("stdio".equals(type)) {
                var command = (String) conn.get("command");
                var args = (List<String>) conn.getOrDefault("args", List.of());
                var params = ServerParameters.builder(command).args(args).build();
                transport = new StdioClientTransport(params, io.modelcontextprotocol.json.McpJsonMapper.getDefault());
            } else if ("streamableHttp".equals(type) || "http".equals(type)) {
                var endpoint = (String) conn.get("url");
                var builder = HttpClientStreamableHttpTransport.builder(endpoint);
                if (authHeader != null) {
                    builder = builder.customizeRequest(req -> req.header("Authorization", authHeader));
                }
                transport = builder.build();
            } else {
                var builder = HttpClientSseClientTransport.builder((String) conn.get("url"));
                if (authHeader != null) {
                    builder = builder.customizeRequest(req -> req.header("Authorization", authHeader));
                }
                transport = builder.build();
            }

            return McpClient.sync(transport).build();
        } catch (Exception e) {
            log.error("Failed to build sync MCP client for {}: {}", mcp.server(), e.getMessage());
            return null;
        }
    }

    /** 从 config.yaml 提取 Authorization header 值；未配置 auth.token 返回 null */
    @SuppressWarnings("unchecked")
    private String extractAuthHeader(Map<String, Object> data) {
        if (!data.containsKey("auth")) {
            return null;
        }
        var auth = (Map<String, Object>) data.get("auth");
        var token = (String) auth.get("token");
        if (token == null || token.isBlank()) {
            return null;
        }
        return "Bearer " + resolveEnv(token);
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
     * @param name          远端裸名（如 get_weather）
     * @param displayName   API 展示名（如 mcp__travel__get_weather）
     * @param description   工具描述
     * @param serverName    MCP 服务器名
     * @param uiResourceUri UI 资源 URI（"ui://..."），无 UI 为 null
     * @param uiSource      UI 元数据来源："config"（config.yaml 声明）/ "auto"（_meta.ui 自动发现）；无 UI 为 null
     * @param appOnly       是否 app_only 工具（仅卡片可调，对 LLM 隐藏）
     */
    public record ToolInfo(
        String name,
        String displayName,
        String description,
        String serverName,
        String uiResourceUri,
        String uiSource,
        boolean appOnly
    ) {
        /** 无 UI 元数据的旧构造（保持兼容） */
        public ToolInfo(String name, String displayName, String description, String serverName) {
            this(name, displayName, description, serverName, null, null, false);
        }
    }

    /**
     * config.yaml ui 段解析结果（MCP Apps 静态声明）。
     *
     * @param tools    普通工具的 UI 声明（工具名 → ui:// URI）
     * @param appOnly  app_only 工具声明（工具名 → ui:// URI，不注册 Toolkit，仅卡片可调）
     * @param csp      CSP 白名单静态声明（connect_domains / resource_domains）
     */
    public record UiMapping(
        Map<String, String> tools,
        Map<String, String> appOnly,
        UiCsp csp
    ) {
        public static UiMapping empty() {
            return new UiMapping(Map.of(), Map.of(), UiCsp.empty());
        }
    }

    /**
     * CSP 白名单静态声明（host 只允许收紧，见 4.4）。
     *
     * @param connectDomains  connect-src 允许的外联域（默认仅同源代理）
     * @param resourceDomains 资源加载允许的外联域（img/media/script 等）
     */
    public record UiCsp(
        List<String> connectDomains,
        List<String> resourceDomains
    ) {
        public static UiCsp empty() {
            return new UiCsp(List.of(), List.of());
        }
    }
}
