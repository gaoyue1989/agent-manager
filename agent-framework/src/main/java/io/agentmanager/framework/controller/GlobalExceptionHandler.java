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
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

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

    /**
     * 沙箱配额满（sandbox-pool-opensandbox-design §4.3 chat 预检，PR-D）：HTTP 409 +
     * 结构化载荷（code/quota/bindings，bindings 带 state=executing|confirm_holding）——
     * 前端 ChatPane 专属错误卡渲染占用明细（评审轮二 P0：409 body 不得丢弃）。
     *
     * <p><b>返回 String body 而非 Map（黑盒第 1 轮实锤缺陷）</b>：chat 端点声明
     * {@code produces = TEXT_EVENT_STREAM}，真实浏览器请求 Accept 只有
     * {@code text/event-stream}——Map body 走 Jackson converter（仅 application/json）
     * 内容协商失败 {@code HttpMediaTypeNotAcceptableException} → 500；String body 走
     * StringHttpMessageConverter（supportedTypes 含 {@code *}/*}）可写任意 selected
     * media type，配合显式 {@code Content-Type: application/json} 头，前端
     * {@code resp.json()}（不依赖响应头）与 curl {@code *}/* 两路径均得 409 结构化载荷。
     */
    @ExceptionHandler(io.agentmanager.framework.sandbox.opensandbox.UserQuotaExhaustedException.class)
    public ResponseEntity<String> handleUserQuotaExhausted(
            io.agentmanager.framework.sandbox.opensandbox.UserQuotaExhaustedException e) {
        log.warn("[ExceptionHandler] user_quota_exhausted: quota={}, bindings={}",
            e.quota(), e.bindings().size());
        String body;
        try {
            body = MAPPER.writeValueAsString(e.payload409());
        } catch (Exception ex) {
            // 序列化兜底：极简载荷保 409 语义（前端 parseQuota409 依赖 code 字段判定）
            body = "{\"code\":\"user_quota_exhausted\",\"quota\":" + e.quota() + ",\"bindings\":[]}";
        }
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, headers, HttpStatus.CONFLICT);
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
