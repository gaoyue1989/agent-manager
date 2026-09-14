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
        log.warn("File upload exceeded size limit: {}", e.getMessage());
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "file_too_large");
        body.put("message", "File size exceeds the configured upload limit");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "bad_request");
        body.put("message", e.getMessage() != null ? e.getMessage() : "Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("Conflict: {}", e.getMessage());
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "conflict");
        body.put("message", e.getMessage() != null ? e.getMessage() : "State conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
