package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * SandboxFailureClassifier 单测（oa-1110-repro-fixes-design §2.3a/c）：
 * cause 链分类（SDK 异常 / 网络异常 / harness 包装形态兜底）、SSE 可读帧构造。
 */
class SandboxFailureClassifierTest {

    private static final String TIMEOUT_MSG = "Request timed out: timeout";

    @Test
    void sdkTimeoutShouldClassifyAsTimeout() {
        var e = new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxTimeoutException(
            TIMEOUT_MSG, null, false);
        assertEquals(SandboxFailureClassifier.Kind.TIMEOUT,
            SandboxFailureClassifier.kindOf(e));
        assertTrue(SandboxFailureClassifier.transientForCreate(e));
    }

    @Test
    void harnessWrappedTimeoutShouldClassifyByMessageFallback() {
        // 生产形态：SDK 异常被 OpenSandboxClient.create 包成 harness SandboxException（无类型映射）
        var wrapped = new io.agentscope.harness.agent.sandbox.SandboxException(
            io.agentscope.harness.agent.sandbox.SandboxErrorCode.WORKSPACE_START_ERROR,
            "create", "Lifecycle acquire failed: " + TIMEOUT_MSG, null);
        assertEquals(SandboxFailureClassifier.Kind.TIMEOUT,
            SandboxFailureClassifier.kindOf(wrapped));
    }

    @Test
    void gateway502ShouldClassifyAsUnreachable() {
        var e = new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException(
            "502 Bad Gateway", null, 502, new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError(
                "gateway_unreachable", "Could not connect to the backend sandbox"));
        assertEquals(SandboxFailureClassifier.Kind.UNREACHABLE,
            SandboxFailureClassifier.kindOf(e));
    }

    @Test
    void nestedCauseShouldBeFound() {
        var cause = new java.net.ConnectException("connection refused");
        var root = new RuntimeException("acquire failed", cause);
        assertEquals(SandboxFailureClassifier.Kind.CONNECTION,
            SandboxFailureClassifier.kindOf(root));
    }

    @Test
    void nonTransientShouldBeNone() {
        assertNull(null);
        assertEquals(SandboxFailureClassifier.Kind.NONE,
            SandboxFailureClassifier.kindOf(new IllegalArgumentException("bad image")));
        assertFalse(SandboxFailureClassifier.transientForCreate(new IllegalArgumentException("x")));
    }

    @Test
    void sseErrorFrameShouldCarryCodeRetryableAndReadableText() {
        var e = new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxTimeoutException(
            TIMEOUT_MSG, null, false);
        String frame = SandboxFailureClassifier.sseErrorFrame(e);
        assertTrue(frame.contains("\"type\":\"error\""), frame);
        assertTrue(frame.contains("\"code\":\"sandbox_create_failed\""), frame);
        assertTrue(frame.contains("\"retryable\":true"), frame);
        assertTrue(frame.contains("沙箱创建超时"), frame);
        assertTrue(frame.contains("Request timed out"), frame);
    }

    @Test
    void sseErrorFrameShouldReturnNullForNonSandboxException() {
        assertNull(SandboxFailureClassifier.sseErrorFrame(new IllegalArgumentException("x")));
    }
}
