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

    // ===== 配额满 409 × text/event-stream Accept（黑盒第 1 轮实锤缺陷回归）=====

    /** 最小端点：复刻 ChatStreamController#chat 的 produces 声明与预检抛出路径 */
    @org.springframework.web.bind.annotation.RestController
    static class QuotaThrowingController {
        @org.springframework.web.bind.annotation.PostMapping(
            value = "/chat", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
        public reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> chat() {
            throw new io.agentmanager.framework.sandbox.opensandbox.UserQuotaExhaustedException(3,
                java.util.List.of(new io.agentmanager.framework.sandbox.opensandbox.UserQuotaExhaustedException.BindingView(
                    "sid-a", "2026-11-08T00:00:00Z", true, false, null)));
        }
    }

    private org.springframework.test.web.servlet.MockMvc standaloneQuotaMvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new QuotaThrowingController(), new GlobalExceptionHandler()).build();
    }

    @Test
    void quota409SurvivesTextEventStreamAccept() throws Exception {
        // 黑盒实锤场景：真实浏览器 chat 发 Accept: text/event-stream——修复前 Map body 走
        // Jackson（仅 application/json）协商失败 HttpMediaTypeNotAcceptableException → 500
        standaloneQuotaMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/chat").accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("user_quota_exhausted"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.quota")
                .value(3))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.bindings[0].session_id")
                .value("sid-a"));
    }

    @Test
    void quota409AlsoFineForWildcardAccept() throws Exception {
        // curl */* 路径（黑盒已验证为 409 JSON）修复后不回归
        standaloneQuotaMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/chat").accept(org.springframework.http.MediaType.ALL))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("user_quota_exhausted"));
    }
}
