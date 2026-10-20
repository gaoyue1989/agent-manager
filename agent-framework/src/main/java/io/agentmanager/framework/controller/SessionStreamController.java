package io.agentmanager.framework.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionEventTailer;
import io.agentmanager.framework.service.TurnLeaseStore;
import reactor.core.publisher.Flux;

/**
 * 会话订阅与状态端点（durable-sse-plan 改造版）。
 *
 * <p>承载「面向已知会话」的查询操作 &mdash; sessionId 在路径中：
 * <ul>
 *   <li>{@code GET /threads/{sid}/subscribe} &mdash; 重连续传（回放 + 游标追赶，只读共享存储），解耦 SSE 连接与 agent 执行生命周期</li>
 *   <li>{@code GET /threads/{sid}/status} &mdash; 查询 turn 状态（刷新恢复用）</li>
 * </ul>
 *
 * <p>对话入口为 {@link ChatStreamController} 的 {@code POST /threads/chat}（sessionId 在请求体中，可选）。
 */
@RestController
@RequestMapping("/threads/{sessionId}")
public class SessionStreamController {

    private static final Logger log = LoggerFactory.getLogger(SessionStreamController.class);

    private final TurnLeaseStore turnLeaseStore;
    private final AgentRuntimeService runtimeService;
    private final SessionEventStore eventStore;
    private final SessionEventTailer tailer;
    private final io.agentmanager.framework.service.SessionUserStore sessionUserStore;
    private final io.agentmanager.framework.service.TurnIntentStore turnIntentStore;

    public SessionStreamController(AgentRuntimeService runtimeService,
                                   TurnLeaseStore turnLeaseStore,
                                   SessionEventStore eventStore,
                                   SessionEventTailer tailer,
                                   io.agentmanager.framework.service.SessionUserStore sessionUserStore,
                                   io.agentmanager.framework.service.TurnIntentStore turnIntentStore) {
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
        this.eventStore = eventStore;
        this.tailer = tailer;
        this.sessionUserStore = sessionUserStore;
        this.turnIntentStore = turnIntentStore;
    }

    // ===== GET /subscribe：重连续传 =====

    /**
     * 订阅 session 的事件流（回放 + 游标追赶）。
     *
     * <p>跨副本安全：全程只读共享存储，不依赖本 Pod 是否执行过该 session。
     *
     * <ul>
     *   <li>{@code afterSeq}：回放游标，从 lastEventId 之后开始回放</li>
     *   <li>{@code replyId}：turn 标识，仅回放/订阅指定 turn 的事件</li>
     * </ul>
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribe(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) Integer afterSeq,
            @RequestParam(required = false) String replyId) {

        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        // 归属校验（issue #158）：非归属用户 404（错误先于任何 SSE 帧写出）。
        // 必须在方法体内同步 throw——返回 Flux.error 时 MVC 已按 SSE 流式提交响应头，
        // 错误信号无法再改状态码，实测落 500（SEC2 e2e 钉）
        if (!io.agentmanager.framework.service.SessionAccessGuard.ownedBy(
                sessionUserStore, sessionId, headerUserId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.NOT_FOUND, "Session '" + sessionId + "' not found");
        }
        return tailer.tail(sessionId, replyId, afterSeq != null ? afterSeq : 0);
    }

    // ===== GET /status：查询 turn 状态 =====

    /**
     * 查询 session 当前 turn 状态（前端刷新恢复用）。
     *
     * <p>状态判定全部基于 Pod 间共享的存储（MySQL 的 turn_lease / confirm_context，
     * Redis 的 session_event），不使用任何进程内状态——跨副本部署下本 Pod 可能从未执行过该
     * session，也可能残留过期的本地 sink。
     *
     * <p><b>503</b>：session_event 存储不可用时返回 503，而不是一个
     * {@code latest_event_seq: 0} 的成功响应——后者会让前端把游标重置为 0 并重放整场会话。
     * 详见 {@link SessionEventStore#findLatestStrict}。
     *
     * <p>响应示例：
     * <pre>{@code
     * {
     *   "session_id": "...",
     *   "state": "working",          // working / completed / waiting_confirm / interrupted / idle
     *   "latest_event_seq": 42,
     *   "reply_id": "...",
     *   "pending_confirm": null      // 或 HITL/远程确认上下文（远程行附 confirm_key/remote_task，
     *                                //  与 history 的 pendingConfirm 同口径：FIFO 最早未消费行）
     * }
     * }</pre>
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        // 归属校验（issue #158）：非归属用户 404，不泄露会话存在性
        if (!io.agentmanager.framework.service.SessionAccessGuard.ownedBy(
                sessionUserStore, sessionId, headerUserId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "thread_not_found",
                "message", "Session '" + sessionId + "' not found"));
        }
        var pendingConfirm = runtimeService.findPendingConfirm(sessionId);
        // 排队中的 turn 尚未产生任何 session_event（/status 看不到它的 replyId）：
        // 意图行把本轮 turn id 提前暴露出来，刷新后才能按它续传。
        var intent = turnIntentStore != null ? turnIntentStore.find(sessionId) : null;

        // 必须用 strict 版：宽容的 findLatest 在**读不到**时返回 null，于是下面会算出
        // "state": "idle" + "latest_event_seq": 0 的**成功**响应，而前端 chat.js:463 拿它
        // 把游标重置为 0 —— Redis 一恢复就是整场会话重放。存储不可用是可重试的依赖故障，
        // 503 才诚实；前端 tryResumeSSE 的 catch（chat.js:514）会保留游标、跳过本次恢复，
        // 因为 debug/js/api.js 的 get() 对非 2xx 抛错。
        final SessionEventStore.EnvelopedEvent latestEvent;
        try {
            latestEvent = eventStore.findLatestStrict(sessionId);
        } catch (Exception e) {
            log.warn("status: session_event store unavailable for {}: {}", sessionId, e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                    "error", "event_store_unavailable",
                    "message", "Session event store is temporarily unavailable"));
        }

        var probe = tailer.probe(sessionId, latestEvent, pendingConfirm != null);

        String state;
        if (pendingConfirm != null) {
            state = "waiting_confirm";
        } else if (intent != null) {
            // QUEUED：租约还在别人手上或即将到手，但本轮 turn 已确定——按 working 处理；
            // FAILED：排队超时/建立失败，刷新后要能直接看到原因（不再走订阅）。
            state = intent.failed() ? "failed" : "working";
        } else if (probe.running()) {
            state = "working";
        } else if (latestEvent != null
                && SessionEventTailer.isTerminalType(latestEvent.type())) {
            // AGENT_END 与 error 都是 turn 终态（与观察者路径 probe 同口径）：
            // 只认 AGENT_END 会让以 error 结束的 turn 落进 idle——刷新后前端既不续传，
            // 也拿不到失败信号，与对话流的 error 帧语义不一致。
            state = "completed";
        } else if (probe.interrupted()) {
            // 有事件、非终态、无租约、无待确认：执行副本崩溃或被抢占
            state = "interrupted";
        } else {
            state = "idle";
        }

        String replyId = intent != null
            ? intent.replyId()
            : (latestEvent != null && latestEvent.replyId() != null ? latestEvent.replyId() : "");
        if ("working".equals(state) && intent == null && latestEvent != null
                && SessionEventTailer.isTerminalType(latestEvent.type())) {
            // 租约被持有、但最新事件仍是上一 turn 的终态 → 持锁的是「尚未产出任何事件」的
            // 下一 turn。此时把上一 turn 的 replyId 下发出去，客户端续传会按旧 turn 过滤，
            // 永远收不到新 turn 的事件（刷新卡住）。留空让客户端按 latest_event_seq 无过滤续传。
            replyId = "";
        }

        return ResponseEntity.ok(Map.of(
            "session_id", sessionId,
            "state", state,
            "latest_event_seq", latestEvent != null ? latestEvent.seq() : 0,
            "reply_id", replyId,
            "pending_confirm", pendingConfirm != null ? pendingConfirm : "",
            "error", intent != null && intent.failed() && intent.error() != null
                ? intent.error() : ""
        ));
    }
}
