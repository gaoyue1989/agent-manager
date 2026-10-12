package io.agentmanager.framework.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * GlobalExceptionHandler 契约测试：异常响应必须带可直接回显给用户的中文 {@code message}，
 * 不得出现英文兜底文案。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Spring multipart 超限（Controller 之前触发）返回中文 message 且带实际上限 */
    @Test
    void maxUploadSizeShouldReturnChineseMessageWithLimit() {
        var resp = handler.handleMaxUploadSizeExceeded(
            new MaxUploadSizeExceededException(20L * 1024 * 1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.getStatusCode());
        assertEquals("file_too_large", resp.getBody().get("error"));
        var msg = String.valueOf(resp.getBody().get("message"));
        assertTrue(msg.contains("20MB"), "应带上限: " + msg);
        assertTrue(msg.contains("超过限制"), "应为中文描述: " + msg);
    }

    @Test
    void illegalArgumentShouldReturnChineseFallbackWhenMessageBlank() {
        var blank = handler.handleIllegalArgument(new IllegalArgumentException("   "));
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode());
        assertEquals("请求参数不合法", blank.getBody().get("message"));

        var withMsg = handler.handleIllegalArgument(new IllegalArgumentException("技能名非法"));
        assertEquals("技能名非法", withMsg.getBody().get("message"));
    }

    @Test
    void illegalStateShouldReturnChineseFallbackWhenMessageBlank() {
        var resp = handler.handleIllegalState(new IllegalStateException("   "));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("操作状态冲突，请稍后重试", resp.getBody().get("message"));
    }

    @Test
    void unhandledShouldReturnChineseMessage() {
        var resp = handler.handleUnhandledException(new RuntimeException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("internal_error", resp.getBody().get("error"));
        assertEquals("服务器内部错误，请稍后重试", resp.getBody().get("message"));
    }
}
