package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.McpResourceProxy;

/**
 * MCP 资源与调用代理端点（MCP Apps 扩展阶段一）。
 *
 * <ul>
 *   <li>GET  /mcp/{server}/resources/ui?uri=ui://... —— 读取 ui:// 资源 HTML（含 CSP 元数据）</li>
 *   <li>GET  /mcp/{server}/resources —— 列出该 server 全部 ui:// 资源（供前端预拉取）</li>
 *   <li>POST /mcp/{server}/tools/{tool} —— UI 卡片代发工具调用（ask 工具走确认流）</li>
 * </ul>
 */
@RestController
@RequestMapping("/mcp/{server}")
public class McpProxyController {
    private static final Logger log = LoggerFactory.getLogger(McpProxyController.class);

    private final McpResourceProxy resourceProxy;

    public McpProxyController(McpResourceProxy resourceProxy) {
        this.resourceProxy = resourceProxy;
    }

    /** 读取 ui:// 资源 HTML，响应含 CSP 元数据（前端 McpAppHost 注入 <meta http-equiv="Content-Security-Policy">） */
    @GetMapping("/resources/ui")
    public Map<String, Object> readUiResource(@PathVariable String server,
                                              @RequestParam String uri) {
        var resource = resourceProxy.readUiResource(server, uri);
        return Map.of(
            "html", resource.html(),
            "mimeType", resource.mimeType(),
            "csp", resource.csp()
        );
    }

    /** 列出该 server 全部已声明/发现的 ui:// 资源 */
    @GetMapping("/resources")
    public Map<String, Object> listUiResources(@PathVariable String server) {
        List<String> uris = resourceProxy.listUiResources(server);
        return Map.of("server", server, "resources", uris);
    }

    /**
     * UI 卡片代发工具调用。
     * body: {arguments: {...}, confirmed?: boolean}
     * ask 工具未确认时返回 403 + needsConfirm，前端弹 HITL 确认卡片，Approve 后带 confirmed=true 重试。
     */
    @PostMapping("/tools/{tool}")
    public ResponseEntity<?> callTool(@PathVariable String server,
                                      @PathVariable String tool,
                                      @RequestBody ToolCallRequest body) {
        try {
            var result = resourceProxy.callTool(server, tool, body.arguments(), Boolean.TRUE.equals(body.confirmed()));
            return ResponseEntity.ok(Map.of(
                "content", result.content(),
                "isError", result.isError(),
                "structuredContent", result.structuredContent() != null ? result.structuredContent() : Map.of()
            ));
        } catch (McpResourceProxy.NeedsConfirmException e) {
            // 复用 HITL 确认卡片数据格式：403 + needsConfirm + toolCalls
            log.info("MCP {}: tool '{}' requires UI confirmation", server, e.toolName());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "needsConfirm", true,
                "toolCalls", List.of(Map.of(
                    "tool_call_id", java.util.UUID.randomUUID().toString(),
                    "name", e.toolName(),
                    "input", e.arguments()
                ))
            ));
        }
    }

    /** 代理异常 → HTTP 状态码透传 */
    @ExceptionHandler(McpResourceProxy.McpProxyException.class)
    public ResponseEntity<Map<String, Object>> handleProxyException(McpResourceProxy.McpProxyException e) {
        log.warn("MCP proxy error ({}): {}", e.status(), e.getMessage());
        return ResponseEntity.status(e.status()).body(Map.of(
            "error", e.getMessage()
        ));
    }

    /** POST 请求体：arguments 必填，confirmed 可选（ask 确认流） */
    public record ToolCallRequest(Map<String, Object> arguments, Boolean confirmed) {
    }
}