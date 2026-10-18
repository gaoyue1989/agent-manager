package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理：将框架级异常转为 JSON 响应（与 FileController.err 同构）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link MaxUploadSizeExceededException} → 413（Spring multipart 在 Controller 之前拒绝超大文件，默认返回非 JSON 500）</li>
 *   <li>{@link IllegalArgumentException} → 400（Controller/Service 层参数校验失败，Spring 默认返回 500）</li>
 *   <li>{@link IllegalStateException} → 409（并发/状态冲突场景，如重复操作）</li>
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("[ExceptionHandler] file_too_large: {}", e.getMessage());
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "file_too_large");
        long max = e.getMaxUploadSize();
        var limit = max > 0 ? (max / 1024 / 1024) + "MB" : "服务端配置值";
        body.put("message", "上传文件大小超过限制（单文件上限 " + limit + "），请压缩后重试");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[ExceptionHandler] bad_request: {}", e.getMessage());
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "bad_request");
        body.put("message", e.getMessage() != null && !e.getMessage().isBlank()
            ? e.getMessage() : "请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("[ExceptionHandler] conflict: {}", e.getMessage());
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "conflict");
        body.put("message", e.getMessage() != null && !e.getMessage().isBlank()
            ? e.getMessage() : "操作状态冲突，请稍后重试");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * ResponseStatusException 按其自带状态码透传（404/409/413…）。
     * 若无此映射，下方 catch-all 会把它吞成 500——SSE 端点（subscribe/confirm-stream）
     * 的会话归属 404（issue #158）正是经此路径返回，SEC2 e2e 钉。
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException e) {
        log.warn("[ExceptionHandler] {}: {}", e.getStatusCode(), e.getReason());
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "request_rejected");
        body.put("message", e.getReason() != null ? e.getReason() : e.getStatusCode().toString());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandledException(Exception e) {
        log.error("[ExceptionHandler] unhandled exception: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "internal_error");
        body.put("message", "服务器内部错误，请稍后重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
