package io.agentmanager.framework.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.model.OafConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP 资源与调用代理（MCP Apps 扩展阶段一）。
 *
 * <p>背景：agentscope 的 McpClientWrapper 不暴露 resources API 且底层 client private，
 * 资源读取/UI 卡片代发调用需独立连接。本服务按 server 懒加载 SDK 原生 McpSyncClient
 * （同一份 config.yaml 连接配置，MCP server 多连接是协议允许的）。</p>
 *
 * <ul>
 *   <li>readUiResource：读取 ui:// 资源 HTML，仅允许 ui:// scheme 且 uri 必须 ∈ 该 server 声明集合</li>
 *   <li>callTool：代发工具调用（UI 卡片 tools/call 回调），校验已注册工具 + permissions 三态权限</li>
 * </ul>
 */
@Service
public class McpResourceProxy {
    private static final Logger log = LoggerFactory.getLogger(McpResourceProxy.class);

    /** 资源大小上限（1MB，防超大 HTML 拖垮代理） */
    private static final int MAX_RESOURCE_BYTES = 1024 * 1024;

    private final OafConfig oafConfig;
    private final McpToolRegistrar toolRegistrar;

    /** serverName -> 懒连接 McpSyncClient（连接失败不缓存，下次重试） */
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public McpResourceProxy(OafConfig oafConfig, McpToolRegistrar toolRegistrar) {
        this.oafConfig = oafConfig;
        this.toolRegistrar = toolRegistrar;
    }

    /**
     * 按 server 读取 ui:// 资源 HTML（含 CSP 声明）。
     *
     * @param serverName MCP 服务器名（须已注册）
     * @param uri        ui:// 资源 URI（须 ∈ 该 server 静态声明/自动发现集合）
     * @return HTML 内容与 MIME
     * @throws McpProxyException 参数非法（400）/未注册（404）/连接失败（502）/超限（413）
     */
    public UiResource readUiResource(String serverName, String uri) {
        if (serverName == null || serverName.isBlank()) {
            throw new McpProxyException(400, "server name is required");
        }
        if (uri == null || !uri.startsWith(McpToolRegistrar.UI_SCHEME)) {
            throw new McpProxyException(400, "uri must use ui:// scheme");
        }
        // uri 必须 ∈ 该 server 声明集合（防任意资源读取代理）
        if (!toolRegistrar.getUiResourceUris(serverName).contains(uri)) {
            log.warn("MCP {}: ui resource '{}' not in declared set, rejected", serverName, uri);
            throw new McpProxyException(400, "uri not declared for server: " + uri);
        }

        var client = getOrCreateClient(serverName);
        try {
            var result = client.readResource(new McpSchema.ReadResourceRequest(uri));
            var contents = result.contents();
            if (contents == null || contents.isEmpty()) {
                throw new McpProxyException(404, "resource not found: " + uri);
            }
            var content = contents.get(0);
            String html;
            String mimeType = "text/html;profile=mcp-app";
            if (content instanceof McpSchema.TextResourceContents text) {
                html = text.text() != null ? text.text() : "";
                if (text.mimeType() != null) {
                    mimeType = text.mimeType();
                }
            } else if (content instanceof McpSchema.BlobResourceContents blob) {
                // MCP BlobResourceContents.blob() 为 base64 编码字符串
                html = new String(java.util.Base64.getDecoder().decode(blob.blob()), StandardCharsets.UTF_8);
                if (blob.mimeType() != null) {
                    mimeType = blob.mimeType();
                }
            } else {
                throw new McpProxyException(400, "unsupported resource content type for: " + uri);
            }
            var bytes = html.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw new McpProxyException(413, "resource exceeds 1MB limit: " + uri);
            }
            if (html.isEmpty()) {
                throw new McpProxyException(404, "resource is empty: " + uri);
            }
            return new UiResource(html, mimeType, buildCspMap(serverName));
        } catch (McpProxyException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP {}: failed to read ui resource {}: {}", serverName, uri, e.getMessage());
            throw new McpProxyException(502, "failed to read ui resource: " + e.getMessage());
        }
    }

    /**
     * 列出该 server 全部已声明/发现的 ui:// 资源（供前端预拉取）。
     */
    public List<String> listUiResources(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return List.of();
        }
        var uris = new ArrayList<>(toolRegistrar.getUiResourceUris(serverName));
        uris.sort(String::compareTo);
        return uris;
    }

    /**
     * 代发工具调用（UI 卡片 tools/call 回调与 /mcp 代理共用）。
     *
     * <p>权限联动（对齐 4.4）：
     * <ul>
     *   <li>仅允许已注册工具（含 app_only）</li>
     *   <li>permissions.read_only: true → 拒绝 destructive 工具（沿用只读语义）</li>
     *   <li>permissions.tools 声明 deny → 拒绝</li>
     *   <li>permissions.tools 声明 ask → 需确认（confirmed=false 返回 needsConfirm，由前端确认后重试）</li>
     * </ul>
     *
     * @param confirmed 是否已带上 UI 侧确认标记（ask 工具需 true 才放行）
     * @return 调用结果（{content, isError} 原样透传）；ask 未确认时抛 403 NeedsConfirmException
     */
    public CallToolResult callTool(String serverName, String toolName,
                                   Map<String, Object> arguments, boolean confirmed) {
        if (serverName == null || serverName.isBlank() || toolName == null || toolName.isBlank()) {
            throw new McpProxyException(400, "server and tool name are required");
        }
        // 仅允许已注册工具（防任意调用；app_only 工具亦在 registeredTools 中）
        var info = toolRegistrar.getToolsByServer(serverName).stream()
            .filter(t -> t.name().equals(toolName))
            .findFirst().orElseThrow(() -> new McpProxyException(404,
                "tool not registered: " + serverName + ":" + toolName));

        // 空参数校验（允许空 map：无参工具合法）
        if (arguments == null) {
            throw new McpProxyException(400, "arguments is required");
        }

        var behavior = toolRegistrar.getToolPermission(serverName, toolName);
        if ("deny".equals(behavior)) {
            log.warn("MCP {}: tool '{}' call from UI rejected (permissions.tools=deny)", serverName, toolName);
            throw new McpProxyException(403, "tool is denied by permissions.tools");
        }
        if ("ask".equals(behavior) && !confirmed) {
            // 复用 HITL 确认卡片数据格式：403 + needsConfirm + toolCalls
            throw new NeedsConfirmException(toolName, arguments);
        }

        // read_only server：沿用只读语义，拒绝 destructive 写工具
        if (toolRegistrar.isServerReadOnly(serverName) && toolRegistrar.isDestructiveHint(serverName, toolName)) {
            throw new McpProxyException(403, "tool is write/destructive and server is read-only");
        }

        var client = getOrCreateClient(serverName);
        try {
            var result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
            return new CallToolResult(
                result.content() != null ? result.content() : List.of(),
                Boolean.TRUE.equals(result.isError()),
                result.structuredContent()
            );
        } catch (Exception e) {
            log.error("MCP {}: tool call {} failed: {}", serverName, toolName, e.getMessage());
            throw new McpProxyException(502, "tool call failed: " + e.getMessage());
        }
    }

    /** server 连接是否可用（懒加载成功后返回 true） */
    public boolean isServerAvailable(String serverName) {
        return clients.containsKey(serverName) || getOrCreateClient(serverName) != null;
    }

    /** 构建响应 CSP 元数据（阶段一以静态声明为准 + 默认宽松策略；资源动态 _meta.ui.csp 交集为 P2） */
    private Map<String, Object> buildCspMap(String serverName) {
        var csp = toolRegistrar.getUiMapping(serverName).csp();
        return Map.of(
            "default", buildCspValue(csp)
        );
    }

    /** 拼接 CSP 指令值（connect-src 默认 'self' 仅允许同源代理，外联域需 config.yaml 白名单） */
    private String buildCspValue(McpToolRegistrar.UiCsp csp) {
        var sb = new StringBuilder();
        sb.append("default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; ");
        sb.append("img-src 'self' data:; media-src 'self' data:; object-src 'none'; frame-src 'none'; ");
        sb.append("connect-src 'self'");
        appendDomains(sb, csp.connectDomains());
        sb.append("; font-src 'self' data:");
        appendDomains(sb, csp.resourceDomains());
        sb.append("; base-uri 'none'; form-action 'none'");
        return sb.toString();
    }

    private void appendDomains(StringBuilder sb, List<String> domains) {
        for (var d : domains) {
            sb.append(' ').append(d);
        }
    }

    /** 懒连接 + 复用 McpSyncClient；构建失败抛 502（不影响 agent 主链路） */
    private McpSyncClient getOrCreateClient(String serverName) {
        var existing = clients.get(serverName);
        if (existing != null) {
            return existing;
        }
        synchronized (clients) {
            var again = clients.get(serverName);
            if (again != null) {
                return again;
            }
            var mcp = oafConfig.mcpServers().stream()
                .filter(m -> m.server().equals(serverName))
                .findFirst().orElseThrow(() -> new McpProxyException(404, "mcp server not registered: " + serverName));
            var client = toolRegistrar.buildSyncClient(mcp);
            if (client == null) {
                throw new McpProxyException(502, "failed to build client for server: " + serverName);
            }
            try {
                client.initialize();
            } catch (Exception e) {
                log.error("MCP {}: client initialize failed: {}", serverName, e.getMessage());
                throw new McpProxyException(502, "failed to connect to server: " + serverName);
            }
            clients.put(serverName, client);
            log.info("McpResourceProxy: lazy-connected client for server {}", serverName);
            return client;
        }
    }

    /** UI 资源读取结果 */
    public record UiResource(String html, String mimeType, Map<String, Object> csp) {}

    /** 工具调用结果（content + isError + structuredContent 原样透传） */
    public record CallToolResult(List<McpSchema.Content> content, boolean isError, Object structuredContent) {}

    /** 代理异常（status + message） */
    public static class McpProxyException extends RuntimeException {
        private final int status;

        public McpProxyException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** ask 工具未确认（403 + needsConfirm，复用 HITL 确认卡片数据格式） */
    public static class NeedsConfirmException extends RuntimeException {
        private final String toolName;
        private final Map<String, Object> arguments;

        public NeedsConfirmException(String toolName, Map<String, Object> arguments) {
            super("tool requires confirmation: " + toolName);
            this.toolName = toolName;
            this.arguments = arguments;
        }

        public String toolName() {
            return toolName;
        }

        public Map<String, Object> arguments() {
            return arguments;
        }
    }
}