package io.agentmanager.framework.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.a2ajob.A2aJobService;
import io.agentmanager.framework.service.a2ajob.A2aJobService.JobConflictException;
import io.agentmanager.framework.service.a2ajob.A2aJobService.JobInvalidException;
import io.agentmanager.framework.service.a2ajob.A2aJobService.JobUnavailableException;
import io.agentmanager.framework.service.a2ajob.A2aJobService.SendRejectedException;

/**
 * A2A 幂等 Job 端点（Issue #69 §2.1；member 侧业务面，默认关闭）。
 *
 * <pre>
 * POST /a2a/jobs      header: Agent-A2A-Job-Token, Idempotency-Key（回落 body.idempotencyKey）
 *                     body:   {"text": "..."}（≤32KB 非空）
 *                     → 200 {"job":{"idempotencyKey","taskId","state"},"idempotent":bool}
 *                     → 409 同键在途/结果未知（持同键重试直至收敛）
 *                     → 401 token 缺失/错误（A2aJobAuthFilter）；400 键或 text 非法；
 *                       503 并发准入饱和 / Redis 故障（fail-closed）
 * GET  /a2a/jobs/{key} → 200 {"idempotencyKey","taskId","state":"in_progress|done"} / 404
 * </pre>
 *
 * <p>认证在 {@code A2aJobAuthFilter}（/a2a/jobs* 前置）；本类只做语义映射。
 * 发送路径为 loopback {@code message/send}（blocking），单请求最长
 * {@code send-timeout-seconds}（默认 300s）——业务 Ingress 3600s 长超时已就位。
 */
@RestController
@ConditionalOnProperty(prefix = "agent.a2a-job", name = "enabled", havingValue = "true")
public class A2aJobController {

    private static final Logger log = LoggerFactory.getLogger(A2aJobController.class);

    private final A2aJobService service;

    public A2aJobController(A2aJobService service) {
        this.service = service;
    }

    @PostMapping("/a2a/jobs")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @RequestBody(required = false) Map<String, Object> body) {
        var key = headerKey;
        if (key == null || key.isBlank()) {
            key = body == null ? null
                : (body.get("idempotencyKey") instanceof String s && !s.isBlank() ? s : null);
        }
        var text = body == null ? null
            : (body.get("text") instanceof String s ? s : null);
        try {
            return ResponseEntity.ok(service.submit(key, text));
        } catch (JobInvalidException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (JobConflictException e) {
            return failWithRetryAfter(HttpStatus.CONFLICT, e.getMessage());
        } catch (SendRejectedException e) {
            // 确定未受理（member 内部失败，请求本身可能合法）：502，同键可立即重试（CR P1-1）
            return fail(HttpStatus.BAD_GATEWAY, e.getMessage());
        } catch (JobUnavailableException e) {
            return failWithRetryAfter(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    @GetMapping("/a2a/jobs/{key}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable("key") String key) {
        if (key == null || !A2aJobService.KEY_PATTERN.matcher(key).matches()) {
            return fail(HttpStatus.BAD_REQUEST, "Idempotency-Key must match [A-Za-z0-9._-]{1,128}");
        }
        try {
            var job = service.status(key);
            return job == null
                ? ResponseEntity.notFound().<Map<String, Object>>build()
                : ResponseEntity.ok(job);
        } catch (JobUnavailableException e) {
            return failWithRetryAfter(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String message) {
        log.debug("[A2aJob] request failed: {} {}", status, message);
        return ResponseEntity.status(status)
            .body(Map.of("error", message == null ? "unknown" : message));
    }

    /** 409/503 附 Retry-After（客户端同键退避重试直至收敛，Issue §2.1） */
    private static ResponseEntity<Map<String, Object>> failWithRetryAfter(HttpStatus status, String message) {
        return ResponseEntity.status(status)
            .header("Retry-After", "5")
            .body(Map.of("error", message == null ? "unknown" : message));
    }
}
