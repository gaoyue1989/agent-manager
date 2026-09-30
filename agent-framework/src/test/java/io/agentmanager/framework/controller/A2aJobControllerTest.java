package io.agentmanager.framework.controller;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentA2aJobProperties;
import io.agentmanager.framework.service.a2ajob.A2aJobService;
import io.agentmanager.framework.service.a2ajob.A2aJobService.JobConflictException;
import io.agentmanager.framework.service.a2ajob.A2aJobService.JobInvalidException;
import io.agentmanager.framework.service.a2ajob.A2aJobService.JobUnavailableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2aJobController 端点映射单测（Issue #69 §2.1）：200 幂等命中 / 400 校验 /
 * 409 Conflict+Retry-After / 503 Unavailable+Retry-After / GET 404 / 键非法 400。
 * 认证在 A2aJobAuthFilter（独立测试），本类只验证语义映射。
 */
class A2aJobControllerTest {

    private final A2aJobService service = mock(A2aJobService.class);
    private final A2aJobController controller = new A2aJobController(service);

    @Test
    void submitShouldReturnOkPayload() {
        when(service.submit("k1", "hello")).thenReturn(Map.of(
            "job", Map.of("idempotencyKey", "k1", "taskId", "t1", "state", "done"),
            "idempotent", true));

        var resp = controller.submit("k1", Map.of("text", "hello"));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.TRUE, resp.getBody().get("idempotent"));
    }

    @Test
    void submitShouldFallBackToBodyKey() {
        when(service.submit("k-body", "x")).thenReturn(Map.of("idempotent", false));
        var resp = controller.submit(null, Map.of("text", "x", "idempotencyKey", "k-body"));
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void submitShouldMapInvalidTo400() {
        when(service.submit("bad", "x")).thenThrow(new JobInvalidException("key invalid"));
        var resp = controller.submit("bad", Map.of("text", "x"));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void submitShouldMapConflictTo409WithRetryAfter() {
        when(service.submit("k2", "x")).thenThrow(new JobConflictException("in flight"));
        var resp = controller.submit("k2", Map.of("text", "x"));
        assertEquals(409, resp.getStatusCode().value());
        assertEquals("5", resp.getHeaders().getFirst("Retry-After"));
        assertTrue(resp.getBody().containsKey("error"));
    }

    @Test
    void submitShouldMapUnavailableTo503WithRetryAfter() {
        when(service.submit("k3", "x")).thenThrow(new JobUnavailableException("redis unavailable"));
        var resp = controller.submit("k3", Map.of("text", "x"));
        assertEquals(503, resp.getStatusCode().value());
        assertEquals("5", resp.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void statusShouldMapNotFoundTo404() {
        when(service.status("missing")).thenReturn(null);
        var resp = controller.status("missing");
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void statusShouldRejectIllegalKeyFormat() {
        var resp = controller.status("bad key!");
        assertEquals(400, resp.getStatusCode().value());
    }

    // ===== A2aJobService.KEY_PATTERN 常量可见性（Controller GET 校验共用） =====

    @Test
    void keyPatternShouldBeSharedContract() {
        assertTrue(A2aJobService.KEY_PATTERN.matcher("a2a-job.Key_1").matches());
        assertEquals(false, A2aJobService.KEY_PATTERN.matcher("bad key").matches());
    }
}
