package io.agentmanager.framework.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.config.VikingConfig;

/**
 * OpenViking 记忆数据面客户端（openviking-memory-integration-design §6）。
 *
 * <p>身份：per-user user key 全部本地确定性推导（三段式
 * {@code b64url(account).b64url(user).b64url(hex(sha256(user+"\0"+seed)))}，PoC 261116
 * 与服务端逐字节一致，Go 侧同公式见 backend DeriveVikingUserKey，测试向量双端对齐），
 * 零 key 存储；首个请求 401（用户未注册）时以服务身份 ADMIN key 幂等开户（seed 派生）
 * 后重试一次。
 *
 * <p>fail-soft 契约（§4.6/§10）：OpenViking 不可用/超时降级为无记忆，所有公开方法
 * 返回空值而不抛异常；search 必须检查 {@code stats.retrieval_error}——上游 embedding
 * 429 时 mode=context 会静默返回 ok+空 entries（PoC 实测），不检查会被误判为
 * 「用户没有记忆」。
 *
 * <p>多副本安全：客户端无状态（key 本地推导、session 映射为 Pod 本地缓存，丢失仅导致
 * 重建 OpenViking session，不影响正确性语义）。
 */
public class VikingMemoryClient {

    private static final Logger log = LoggerFactory.getLogger(VikingMemoryClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** OpenViking user_id 合法字符集（官方口径，超集校验防注入路径段） */
    private static final String USER_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9_.@-]*";

    private final VikingConfig cfg;
    private final HttpClient http;
    private final Map<String, String> ovSessionIds = new ConcurrentHashMap<>();
    /** createSession 失败负缓存（platformSessionId → 失败时刻 epoch ms），冷却期内 append 快速丢弃 */
    private final Map<String, Long> failedSessionAt = new ConcurrentHashMap<>();

    static final long SESSION_RETRY_COOLDOWN_MS = 30_000;

    public VikingMemoryClient(VikingConfig cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    // ---- 身份：本地推导 + lazy 开户 ----

    /** 三段式 user key 本地推导（与 Go DeriveVikingUserKey 同公式同向量） */
    static String deriveUserKey(String account, String user, String seed) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest((user + "\u0000" + seed).getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            var seg = Base64.getUrlEncoder().withoutPadding();
            return seg.encodeToString(account.getBytes(StandardCharsets.UTF_8))
                + "." + seg.encodeToString(user.getBytes(StandardCharsets.UTF_8))
                + "." + seg.encodeToString(hex.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** user key for 请求头（非法 userId 返回 empty，调用方按无记忆处理） */
    private String keyFor(String userId) {
        if (userId == null || userId.isBlank() || !userId.matches(USER_ID_PATTERN)) {
            return null;
        }
        return deriveUserKey(cfg.account(), userId, cfg.userSeed());
    }

    /**
     * lazy 开户：以服务身份 ADMIN key 注册 (account, user)（seed 派生，幂等——已存在
     * 视为成功）。仅在数据请求 401 后调用（正常路径零外呼）。
     */
    private boolean ensureUser(String userId) {
        if (cfg.adminKey().isBlank()) {
            return false;
        }
        try {
            var req = jsonReqBuilder("POST", cfg.url() + "/api/v1/admin/accounts/" + cfg.account() + "/users",
                cfg.adminKey(),
                MAPPER.writeValueAsString(Map.of("user_id", userId, "role", "user", "seed", cfg.userSeed()))).build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            // 200/201=新建；409/CONFLICT=已存在（幂等成功）；其余失败告警
            if (resp.statusCode() < 300 || isConflict(resp)) {
                return true;
            }
            log.warn("[viking] ensureUser {} failed: HTTP {} {}", userId, resp.statusCode(),
                abbreviate(resp.body()));
            return false;
        } catch (Exception e) {
            log.warn("[viking] ensureUser {} failed: {}", userId, e.getMessage());
            return false;
        }
    }

    private static boolean isConflict(HttpResponse<String> resp) {
        return resp.statusCode() == 409 || resp.body().contains("CONFLICT")
            || resp.body().contains("ALREADY_EXISTS") || resp.body().contains("EXISTS");
    }

    // ---- 数据面（per-user key 身份）----

    /**
     * 首推理检索：search(mode=context) 返回注入就绪的上下文文本；空/失败/超时返回 null
     * （调用方降级为无记忆）。含 stats.retrieval_error 判据（§10 风险表）。
     */
    public String searchContext(String userId, String query) {
        var key = keyFor(userId);
        if (key == null || query == null || query.isBlank()) {
            return null;
        }
        try {
            var body = MAPPER.writeValueAsString(Map.of(
                "query", query, "mode", "context", "limit", 5));
            var resp = sendWithReauth("POST", cfg.url() + "/api/v1/search/search", key, userId, body);
            if (resp == null || resp.statusCode() >= 300) {
                return null;
            }
            var root = MAPPER.readTree(resp.body());
            if (!"ok".equalsIgnoreCase(root.path("status").asText())) {
                return null;
            }
            // 上游供给故障被包装为 ok+空结果：retrieval_error 非空视为检索失败（降级无记忆）
            var stats = root.path("result").path("stats").path("retrieval_error");
            if (!stats.isMissingNode() && !stats.isNull()
                && (stats.size() > 0 || (stats.isTextual() && !stats.asText().isBlank()))) {
                log.warn("[viking] search retrieval_error for {}: {}", userId, abbreviate(stats.toString()));
                return null;
            }
            var rendered = root.path("result").path("rendered").asText("");
            return rendered.isBlank() ? null : rendered;
        } catch (Exception e) {
            log.warn("[viking] searchContext failed for {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 逐 turn 记忆提取提交（OVK_SESSION_COMMIT=false 路径）：messages 为本轮
     * [user 提问, assistant 回复] 文本；提交即返（提取全后台），失败仅告警。
     */
    public void remember(String userId, List<Map.Entry<String, String>> roleAndContent) {
        var key = keyFor(userId);
        if (key == null || roleAndContent == null || roleAndContent.isEmpty()) {
            return;
        }
        try {
            var messages = MAPPER.createArrayNode();
            for (var pair : roleAndContent) {
                var m = messages.addObject();
                m.put("role", pair.getKey());
                m.put("content", pair.getValue());
            }
            var root = MAPPER.createObjectNode();
            root.set("messages", messages);
            // remember 无 REST 端点，官方入口为 MCP tools/call（PoC 261116 实测 66ms 即返）
            var resp = sendMcpToolCall(key, userId, "remember", root.toString());
            if (resp == null || resp.statusCode() >= 300) {
                log.warn("[viking] remember submit failed for {}: HTTP {}", userId,
                    resp == null ? "null" : resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("[viking] remember failed for {}: {}", userId, e.getMessage());
        }
    }

    // ---- 会话归档（OVK_SESSION_COMMIT=true 路径）----

    /**
     * 追加一条会话消息到 OpenViking session（Pod 本地缓存 sid→ovSid 映射，丢失重建）。
     * 提取由服务端 auto_commit_policy 调度，本方法只负责 append（异步语义由调用方保证）。
     *
     * <p>createSession 失败负缓存 {@link #SESSION_RETRY_COOLDOWN_MS}：OpenViking 不可用时
     * 冷却期内同会话 append 快速丢弃，避免每条消息都撞一次 searchTimeoutMs（issue #418）。
     */
    public void appendSessionMessage(String userId, String platformSessionId, String role, String content) {
        var key = keyFor(userId);
        if (key == null || platformSessionId == null || platformSessionId.isBlank()) {
            return;
        }
        try {
            var ovSid = ovSessionIds.get(platformSessionId);
            if (ovSid == null) {
                var failedAt = failedSessionAt.get(platformSessionId);
                if (failedAt != null && System.currentTimeMillis() - failedAt < SESSION_RETRY_COOLDOWN_MS) {
                    return;
                }
                ovSid = createSession(key, userId);
                if (ovSid == null) {
                    failedSessionAt.put(platformSessionId, System.currentTimeMillis());
                    log.warn("[viking] create session failed (sid={}), dropping appends for {}ms",
                        platformSessionId, SESSION_RETRY_COOLDOWN_MS);
                    return;
                }
                failedSessionAt.remove(platformSessionId);
                ovSessionIds.put(platformSessionId, ovSid);
            }
            var body = MAPPER.writeValueAsString(Map.of("role", role, "content", content));
            var resp = sendWithReauth("POST", cfg.url() + "/api/v1/sessions/" + ovSid + "/messages",
                key, userId, body);
            if (resp == null || resp.statusCode() >= 300) {
                log.warn("[viking] append session message failed (sid={}): HTTP {}", platformSessionId,
                    resp == null ? "null" : resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("[viking] appendSessionMessage failed (sid={}): {}", platformSessionId, e.getMessage());
        }
    }

    /** 建会话（带 auto_commit_policy：100 条/150k token 阈值 + 30min idle 兜底） */
    private String createSession(String key, String userId) {
        try {
            var body = MAPPER.writeValueAsString(Map.of("auto_commit_policy", Map.of(
                "message_count_threshold", 100,
                "pending_token_threshold", 150000,
                "idle_timeout_seconds", 1800)));
            var resp = sendWithReauth("POST", cfg.url() + "/api/v1/sessions", key, userId, body);
            if (resp == null || resp.statusCode() >= 300) {
                return null;
            }
            var id = MAPPER.readTree(resp.body()).path("result").path("session_id").asText("");
            return id.isBlank() ? null : id;
        } catch (Exception e) {
            log.warn("[viking] createSession failed: {}", e.getMessage());
            return null;
        }
    }

    // ---- HTTP 基础设施 ----

    /** 发送数据请求；401（用户未注册）时开户重试一次（幂等） */
    private HttpResponse<String> sendWithReauth(String method, String url, String key,
                                                String userId, String jsonBody) throws Exception {
        var resp = exchange(method, url, key, jsonBody, cfg.searchTimeoutMs());
        if (resp.statusCode() == 401 && ensureUser(userId)) {
            resp = exchange(method, url, key, jsonBody, cfg.searchTimeoutMs());
        }
        return resp;
    }

    /** MCP tools/call（服务级 remember 入口；SSE 或纯 JSON 响应均兼容） */
    private HttpResponse<String> sendMcpToolCall(String key, String userId, String tool, String argsJson)
            throws Exception {
        var payload = MAPPER.writeValueAsString(Map.of(
            "jsonrpc", "2.0", "id", 1, "method", "tools/call",
            "params", Map.of("name", tool, "arguments", MAPPER.readTree(argsJson))));
        var resp = exchange("POST", cfg.url() + "/mcp", key, payload, 10_000);
        if (resp.statusCode() == 401 && ensureUser(userId)) {
            resp = exchange("POST", cfg.url() + "/mcp", key, payload, 10_000);
        }
        return resp;
    }

    private HttpResponse<String> exchange(String method, String url, String key, String jsonBody, int timeoutMs)
            throws Exception {
        var builder = jsonReqBuilder(method, url, key, jsonBody);
        if (timeoutMs > 0) {
            builder = builder.timeout(Duration.ofMillis(timeoutMs));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 构建请求（返回 Builder，调用方按需 .timeout() 后 send） */
    private HttpRequest.Builder jsonReqBuilder(String method, String url, String key, String jsonBody)
            throws Exception {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("X-API-Key", key)
            .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (url.endsWith("/mcp")) {
            builder.header("Accept", "application/json, text/event-stream");
        }
        return builder;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
