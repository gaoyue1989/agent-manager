package io.agentmanager.framework.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.OafReloadService;

/**
 * 运维端点：OAF 配置 reload 触发（docs/oaf-dynamic-reload-plan.md §4.4）。
 *
 * <p>POST /admin/reload?scope=auto|mcp|agent（默认 auto）；GET /admin/reload 为只读状态。
 * <ul>
 *   <li>auto：指纹比对自动分流（仅 mcp-configs 变 → 原地 reload；AGENTS.md 变 → 整包重建）；</li>
 *   <li>mcp：全部 MCP server 原地 reload；</li>
 *   <li>agent：整包重建 HarnessAgent（含 MCP 全量注册）；</li>
 * </ul>
 *
 * <p>触发方保证"PVC 写完再触发"；失败时旧配置继续服务，返回 500 + 结构化错误。
 * 鉴权：与 /debug/* 同级（集群内网入口后），未启用独立 token（开放问题 2）。
 */
@RestController
@RequestMapping("/admin/reload")
public class AdminReloadController {
    private static final Logger log = LoggerFactory.getLogger(AdminReloadController.class);

    private final OafReloadService reloadService;

    public AdminReloadController(OafReloadService reloadService) {
        this.reloadService = reloadService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> reload(
            @RequestParam(defaultValue = "auto") String scope,
            @RequestParam(required = false) String server) {
        log.info("[Reload] triggered via endpoint: scope={}, server={}", scope, server);
        try {
            switch (scope) {
                case "mcp" -> {
                    var result = server != null && !server.isBlank()
                        ? reloadService.reloadMcpServer(server)
                        : reloadService.reloadMcpAll();
                    return ResponseEntity.ok(toMap(result));
                }
                case "agent" -> {
                    var result = reloadService.reloadAgent();
                    return ResponseEntity.ok(toMap(result));
                }
                case "auto" -> {
                    var result = reloadService.reloadDebounced();
                    return ResponseEntity.ok(toMap(result));
                }
                default -> {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "invalid scope: " + scope + " (expected auto|mcp|agent)"));
                }
            }
        } catch (Exception e) {
            log.error("[Reload] failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "scope", scope,
                "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                "note", "old configuration remains active"
            ));
        }
    }

    /** 只读诊断：当前 MCP server 注册状态（GET，无副作用）。 */
    @GetMapping
    public Map<String, Object> status() {
        return Map.of(
            "scope", "status",
            "registeredServers", reloadService.registeredServerStatus()
        );
    }

    private Map<String, Object> toMap(OafReloadService.ReloadResult result) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("scope", result.scope());
        map.put("fingerprint_changed", result.fingerprintChanged());
        map.put("agent_rebuilt", result.agentRebuilt());
        map.put("mcp_servers", result.mcpServers());
        if (result.error() != null) {
            map.put("error", result.error());
        }
        return map;
    }
}
