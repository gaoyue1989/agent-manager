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

    // ===== issue #383：SSE 可读化域门控（非沙箱域一律原样透传） =====

    @Test
    void llmStreamErrorShouldNotProduceSandboxFrame() {
        // OpenAIClient streaming 实际形态：LLM 端点不可达时 cause 链含裸 ConnectException，
        // 沙箱从未参与——不得误报成 sandbox_create_failed「沙箱连接失败」
        var e = new RuntimeException(
            "HTTP transport error during streaming: SSE/NDJSON stream failed: java.net.ConnectException: Connection refused",
            new java.io.IOException("SSE/NDJSON stream failed",
                new java.net.ConnectException("Connection refused")));
        assertNull(SandboxFailureClassifier.sseErrorFrame(e));
        // kindOf 语义不变（SandboxLifecycleManager create 重试与 OpenSandbox 数据面计数依赖）
        assertEquals(SandboxFailureClassifier.Kind.CONNECTION,
            SandboxFailureClassifier.kindOf(e));
    }

    @Test
    void harnessSandboxExceptionShouldStillProduceFrame() {
        var wrapped = new io.agentscope.harness.agent.sandbox.SandboxException(
            io.agentscope.harness.agent.sandbox.SandboxErrorCode.WORKSPACE_START_ERROR,
            "create", "Lifecycle acquire failed: " + TIMEOUT_MSG, null);
        String frame = SandboxFailureClassifier.sseErrorFrame(wrapped);
        assertTrue(frame != null && frame.contains("沙箱创建超时"), frame);
    }

    @Test
    void sdkSandboxExceptionFamilyShouldStillProduceFrame() {
        var e = new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxConnectionException(
            "connection refused", null, false);
        String frame = SandboxFailureClassifier.sseErrorFrame(e);
        assertTrue(frame != null && frame.contains("沙箱连接失败"), frame);
    }

    @Test
    void harnessWrapperMessagePrefixesShouldStillProduceFrame() {
        // harness 包装专属前缀（OpenSandboxClient create/start 两处）+ ConnectException cause → CONNECTION 帧
        var acquire = new RuntimeException(
            "Lifecycle acquire failed: java.net.ConnectException: Connection refused",
            new java.net.ConnectException("Connection refused"));
        String acquireFrame = SandboxFailureClassifier.sseErrorFrame(acquire);
        assertTrue(acquireFrame != null && acquireFrame.contains("沙箱连接失败"), acquireFrame);

        var start = new RuntimeException(
            "Failed to start OpenSandbox: java.net.ConnectException: Connection refused",
            new java.net.ConnectException("Connection refused"));
        String startFrame = SandboxFailureClassifier.sseErrorFrame(start);
        assertTrue(startFrame != null && startFrame.contains("沙箱连接失败"), startFrame);
    }
}
