package io.agentmanager.framework.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.UiContextStore;

/**
 * UI 交互上下文端点（MCP Apps 扩展 4.7）。
 *
 * <p>对应规范 `ui/update-model-context` 请求的宿主侧落点：静默更新模型上下文，
 * 持久化后下次 agent 调用时注入为 system context（不触发新回复，不影响当前流）。
 *
 * <p>POST /mcp/ui-context —— body: {sessionId, content?, structuredContent?}
 */
@RestController
public class UiContextController {
    private static final Logger log = LoggerFactory.getLogger(UiContextController.class);

    private final UiContextStore uiContextStore;

    public UiContextController(UiContextStore uiContextStore) {
        this.uiContextStore = uiContextStore;
    }

    /**
     * 覆盖式持久化 UI 交互上下文。
     * body: {sessionId: "tenant:thread", content?: string, structuredContent?: object}
     */
    @PostMapping("/mcp/ui-context")
    public Map<String, Object> updateContext(@RequestBody UiContextRequest body) {
        var sessionId = body.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        // 格式校验前置（tenant:thread，防跨租户注入）
        UiContextStore.validateSessionId(sessionId);
        if ((body.content() == null || body.content().isBlank())
                && body.structuredContent() == null) {
            throw new IllegalArgumentException("content or structuredContent is required");
        }
        uiContextStore.upsert(sessionId, body.content(), body.structuredContent());
        log.info("UI context updated for session {} (content={} chars, structured={})",
            sessionId, body.content() == null ? 0 : body.content().length(),
            body.structuredContent() != null);
        return Map.of("updated", true, "sessionId", sessionId);
    }

    /** 参数非法（400）与持久化失败（500）统一处理 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleError(RuntimeException e) {
        var status = e instanceof IllegalArgumentException
            ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        log.warn("UI context update rejected ({}): {}", status.value(), e.getMessage());
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    /** POST 请求体：sessionId 必填；content / structuredContent 至少一个 */
    public record UiContextRequest(String sessionId, String content, Object structuredContent) {
    }
}