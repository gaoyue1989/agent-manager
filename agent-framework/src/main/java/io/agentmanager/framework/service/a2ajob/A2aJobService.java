package io.agentmanager.framework.service.a2ajob;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.config.AgentA2aJobProperties;

/**
 * A2A 幂等 Job 状态机语义层（Issue #69 §2.1/§2.4/§2.5；member 侧 `/a2a/jobs`）。
 *
 * <p><b>失败分类</b>（沿用 Issue #69 修订后语义，PR #67 实测沉淀）：
 * <ul>
 *   <li>幂等命中（值为 taskId）→ 200 {@code idempotent=true}，不重发；</li>
 *   <li>确定未受理（连接失败 / HTTP 4xx / JSON-RPC error）→ 释放认领，同键可立即重试；</li>
 *   <li>结果未知（超时 / 5xx）→ 保留认领并刷新租期，**禁止重发**（409 直到收敛）；</li>
 *   <li>认领丢失（Lua CAS 0）→ 409（租期过期的并发接管方已入局）。</li>
 * </ul>
 * 语义为 <b>at-least-once</b>：结果未知后的接管重发，首次会话可能已完整执行——副作用由
 * 工具层 plan_id/expected_version 兜底（travel-fulfillment §12）。
 *
 * <p><b>并发准入</b>（Issue §2.5 P0）：{@code message/send}（blocking）同步占一个 Tomcat
 * 线程，loopback 自环再占一个——并发 Job ≈ 线程池上限时全部线程在等自环 → 死锁。
 * {@code Semaphore(maxConcurrent)} tryAcquire(0) 失败即 503 + Retry-After，不排队。
 *
 * <p><b>Redis 语义</b>：运行中 Redis 命令失败一律 503 fail-closed，**无本地降级**——
 * 多副本下本地状态即谎言（Issue §2.4）。
 */
@Service
public class A2aJobService {

    private static final Logger log = LoggerFactory.getLogger(A2aJobService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 幂等键字符集（同 PR #67）：[A-Za-z0-9._-]{1,128} */
    public static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    /** text 上限（Issue §2.1） */
    static final int MAX_TEXT_BYTES = 32 * 1024;
    /** taskId 以 claim: 开头视为异常拒绝（防御性；实际为 UUID） */
    static final String CLAIM_PREFIX = A2aJobRedisStore.CLAIM_PREFIX;

    private final A2aJobRedisStore store;
    private final AgentA2aJobProperties props;
    private final Semaphore permits;
    private final String serverPort;
    private final HttpClient http;
    /** 租期 = 2×发送超时 + 60s（覆盖 blocking 发送全程 + 收口窗口） */
    private final Duration lease;

    public A2aJobService(A2aJobRedisStore store,
                         AgentA2aJobProperties props,
                         @Value("${server.port:8100}") String serverPort) {
        this.store = store;
        this.props = props;
        this.permits = new Semaphore(Math.max(1, props.maxConcurrent()));
        this.serverPort = serverPort;
        this.lease = Duration.ofSeconds(props.sendTimeoutSeconds() * 2L + 60);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /** 当前可用许可（InfoController 透出观测用） */
    public int availablePermits() {
        return permits.availablePermits();
    }

    /**
     * 提交 Job（同键幂等）。
     *
     * @return {@code {"job":{...}, "idempotent":bool}}；抛 {@link JobConflictException}
     *         = 409（in-flight/结果未知/认领丢失）、{@link JobUnavailableException} = 503
     */
    public Map<String, Object> submit(String idempotencyKey, String text) {
        if (idempotencyKey == null || !KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new JobInvalidException("Idempotency-Key must match [A-Za-z0-9._-]{1,128}");
        }
        if (text == null || text.isBlank()) {
            throw new JobInvalidException("text must not be blank");
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new JobInvalidException("text exceeds 32KB");
        }

        // 并发准入（不排队）：503 + Retry-After
        if (!permits.tryAcquire()) {
            throw new JobUnavailableException("concurrent job admission saturated");
        }
        try {
            return submitWithPermit(idempotencyKey, text);
        } finally {
            permits.release();
        }
    }

    private Map<String, Object> submitWithPermit(String idempotencyKey, String text) {
        // 1. 抢认领（SET NX EX）：多副本并发同键由 Redis 单键原子性裁决；claim 残留
        //    （瞬时进程死亡）时「复查键消失 → 重试 NX」恰一次（RetryOnceSignal）
        String token = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {            String existing;
            try {
                existing = store.claim(idempotencyKey, token, lease);
            } catch (Exception e) {
                log.error("[A2aJob] redis claim failed for {}: {}", idempotencyKey, e.getMessage());
                throw new JobUnavailableException("redis unavailable");
            }
            if (existing == null) {
                break;   // 认领成功
            }
            if (!existing.startsWith(CLAIM_PREFIX)) {
                // 幂等命中：返回既有 taskId 不重发（Issue §2.1）
                return jobPayload(idempotencyKey, existing, "done", true);
            }
            try {
                handleClaimResidue(idempotencyKey);
            } catch (RetryOnceSignal retry) {
                if (attempt == 1) {
                    throw new JobConflictException("job in flight; retry with the same Idempotency-Key until it settles");
                }
                continue;   // 键已消失：下一轮 NX
            } catch (Exception ignored) {
                throw new JobConflictException("job in flight; retry with the same Idempotency-Key until it settles");
            }
            throw new JobConflictException("job in flight; retry with the same Idempotency-Key until it settles");
        }

        // 2. loopback message/send（blocking=true；wire 语义与 PR #67 部署实测一致）
        String taskId;
        try {
            taskId = sendBlocking(idempotencyKey, text);
        } catch (SendRejectedException e) {
            // 确定未受理：释放认领，同键可立即重试
            releaseQuietly(idempotencyKey, token);
            throw e;
        } catch (SendUnknownException e) {
            // 结果未知：保留认领 + 刷新租期，禁重发
            try {
                store.extendLease(idempotencyKey, token, lease);
            } catch (Exception ex) {
                log.error("[A2aJob] extend lease failed for {}: {}", idempotencyKey, ex.getMessage());
                throw new JobUnavailableException("redis unavailable");
            }
            throw new JobConflictException("job outcome unknown; retry with the same Idempotency-Key until it settles");
        } catch (Exception e) {
            // Redis 异常等：fail-closed（认领状态未知 → 不可安全重发）
            log.error("[A2aJob] submit failed for {}: {}", idempotencyKey, e.getMessage());
            throw new JobUnavailableException("redis unavailable");
        }

        // 3. 落映射（Lua CAS）：CAS 失败 = 认领已被接管（409，接管方会重发——at-least-once）
        boolean settled;
        try {
            settled = store.completeWithTaskId(idempotencyKey, token, taskId,
                Duration.ofHours(props.retentionHours()));
        } catch (Exception e) {
            log.error("[A2aJob] redis complete failed for {}: {}", idempotencyKey, e.getMessage());
            throw new JobUnavailableException("redis unavailable");
        }
        if (!settled) {
            throw new JobConflictException("claim lost; job may have been resubmitted by another replica");
        }
        log.info("[A2aJob] submitted: key={}, taskId={}", idempotencyKey, taskId);
        return jobPayload(idempotencyKey, taskId, "done", false);
    }

    /** claim 残留处理：立即复查一次，键消失 → RetryOnceSignal（外层重试 NX）；仍占用 → 409 */
    private void handleClaimResidue(String idempotencyKey) {
        try {
            Thread.sleep(50);
            if (store.get(idempotencyKey) == null) {
                throw RetryOnceSignal.INSTANCE;
            }
        } catch (RetryOnceSignal retry) {
            throw retry;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 查询失败按占用处理
        }
    }

    /** GET /a2a/jobs/{key}：映射查询（无本地降级；Redis 故障 → 503） */
    public Map<String, Object> status(String idempotencyKey) {
        String v;
        try {
            v = store.get(idempotencyKey);
        } catch (Exception e) {
            log.error("[A2aJob] redis get failed for {}: {}", idempotencyKey, e.getMessage());
            throw new JobUnavailableException("redis unavailable");
        }
        if (v == null) {
            return null;
        }
        if (v.startsWith(CLAIM_PREFIX) || v.isBlank()) {
            return jobPayload(idempotencyKey, "", "in_progress", false);
        }
        return jobPayload(idempotencyKey, v, "done", false);
    }

    // ---------------------------------------------------------------- loopback

    /**
     * loopback POST / （A2AController 全量透传 SDK）——message/send 最小 JSON-RPC 报文，
     * 兼容字段由 A2AController.normalizeMessageSendBody 兜底补全。 taskId 三级回落
     * （result.taskId → result.id → result.task.id）与 PR #67 部署实测一致。
     */
    /** 发送入口（包可见可覆写：测试注入 5xx/超时等结果未知路径） */
    String sendBlocking(String idempotencyKey, String text) {
        return sendBlockingViaHttp(idempotencyKey, text);
    }

    private String sendBlockingViaHttp(String idempotencyKey, String text) {
        String body;
        try {
            body = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", idempotencyKey,
                "method", "message/send",
                "params", Map.of(
                    "message", Map.of(
                        "role", "user",
                        "kind", "message",
                        "messageId", "job-" + idempotencyKey,
                        "parts", List.of(Map.of("kind", "text", "text", text))),
                    "configuration", Map.of("blocking", true))));
        } catch (Exception e) {
            throw new SendRejectedException("build request failed: " + e.getMessage());
        }
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + serverPort + "/"))
            .timeout(Duration.ofSeconds(props.sendTimeoutSeconds()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SendUnknownException("send timeout after " + props.sendTimeoutSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SendUnknownException("send interrupted");
        } catch (java.io.IOException e) {
            throw new SendRejectedException("transport error: " + e.getMessage());
        }
        if (resp.statusCode() >= 500) {
            throw new SendUnknownException("member HTTP " + resp.statusCode());
        }
        if (resp.statusCode() >= 400) {
            throw new SendRejectedException("member HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        return extractTaskId(resp.body());
    }

    /** taskId 三级回落；error 体 = 确定拒绝；无法提取 = 结果未知（任务可能已建） */
    static String extractTaskId(String jsonRpcBody) {
        try {
            var root = MAPPER.readTree(jsonRpcBody);
            if (root.has("error") && !root.get("error").isNull()) {
                throw new SendRejectedException("json-rpc error: " + truncate(root.get("error").toString()));
            }
            var result = root.get("result");
            if (result != null && !result.isNull()) {
                for (var path : new String[]{"taskId", "id"}) {
                    if (result.has(path) && !result.get(path).isNull()
                            && !result.get(path).asText().isBlank()) {
                        var v = result.get(path).asText();
                        if (v.startsWith(CLAIM_PREFIX)) {
                            throw new SendRejectedException("abnormal taskId (claim-prefixed): " + truncate(v));
                        }
                        return v;
                    }
                }
                var task = result.get("task");
                if (task != null && !task.isNull() && task.has("id") && !task.get("id").isNull()
                        && !task.get("id").asText().isBlank()) {
                    return task.get("id").asText();
                }
            }
            throw new SendUnknownException("no taskId in response");
        } catch (SendRejectedException | SendUnknownException e) {
            throw e;
        } catch (Exception e) {
            throw new SendUnknownException("unparseable response");
        }
    }

    private void releaseQuietly(String idempotencyKey, String token) {
        try {
            if (!store.release(idempotencyKey, token)) {
                log.warn("[A2aJob] release lost for {} (claim taken over)", idempotencyKey);
            }
        } catch (Exception e) {
            // 释放失败：租期到期自愈（2×超时+60s）；期间同键 409
            log.error("[A2aJob] release failed for {}: {} (lease expires to self-heal)",
                idempotencyKey, e.getMessage());
        }
    }

    private static Map<String, Object> jobPayload(String key, String taskId, String state, boolean idempotent) {
        var job = new LinkedHashMap<String, Object>();
        job.put("idempotencyKey", key);
        job.put("taskId", taskId);
        job.put("state", state);
        var out = new LinkedHashMap<String, Object>();
        out.put("job", job);
        out.put("idempotent", idempotent);
        return out;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ---------------------------------------------------------------- 内部信号与异常

    /** handleExisting 内部重试信号（避免回环重构；外层 catch 转 claim 重试一次） */
    static final class RetryOnceSignal extends RuntimeException {
        static final RetryOnceSignal INSTANCE = new RetryOnceSignal();
    }

    /** 幂等命中信号（submit 内部捕获后转 200 返回） */
    static final class IdempotentHitSignal extends RuntimeException {
        final String taskId;

        IdempotentHitSignal(String taskId) {
            super(taskId, null, false, false);
            this.taskId = taskId;
        }
    }

    /** 400：键或 text 非法 */
    public static final class JobInvalidException extends RuntimeException {
        public JobInvalidException(String message) { super(message); }
    }

    /** 409：同键在途 / 结果未知 / 认领丢失 */
    public static final class JobConflictException extends RuntimeException {
        public JobConflictException(String message) { super(message); }
    }

    /** 503：并发饱和 / Redis 故障（fail-closed） */
    public static final class JobUnavailableException extends RuntimeException {
        public JobUnavailableException(String message) { super(message); }
    }

    /** 确定未受理（连接失败 / 4xx / JSON-RPC error）：可释放重试 */
    static final class SendRejectedException extends RuntimeException {
        SendRejectedException(String message) { super(message); }
    }

    /** 结果未知（超时 / 5xx / 无法解析）：保留认领禁重发 */
    static final class SendUnknownException extends RuntimeException {
        SendUnknownException(String message) { super(message); }
    }
}
