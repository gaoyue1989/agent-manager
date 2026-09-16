package io.agentmanager.framework.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 *   <li>{@code GET /threads/{sid}/subscribe} &mdash; 重连续传（回放 + 游标追赶，只读 DB），解耦 SSE 连接与 agent 执行生命周期</li>
 *   <li>{@code GET /threads/{sid}/status} &mdash; 查询 turn 状态（刷新恢复用）</li>
 * </ul>
 *
 * <p>对话入口为 {@link ChatStreamController} 的 {@code POST /threads/chat}（sessionId 在请求体中，可选）。
 */
@RestController
@RequestMapping("/threads/{sessionId}")
public class SessionStreamController {

    private final TurnLeaseStore turnLeaseStore;
    private final AgentRuntimeService runtimeService;
    private final SessionEventStore eventStore;
    private final SessionEventTailer tailer;

    public SessionStreamController(AgentRuntimeService runtimeService,
                                   TurnLeaseStore turnLeaseStore,
                                   SessionEventStore eventStore,
                                   SessionEventTailer tailer) {
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
        this.eventStore = eventStore;
        this.tailer = tailer;
    }

    // ===== GET /subscribe：重连续传 =====

    /**
     * 订阅 session 的事件流（回放 + 游标追赶）。
     *
     * <p>跨副本安全：全程只读 DB，不依赖本 Pod 是否执行过该 session。
     *
     * <ul>
     *   <li>{@code afterSeq}：回放游标，从 lastEventId 之后开始回放</li>
     *   <li>{@code replyId}：turn 标识，仅回放/订阅指定 turn 的事件</li>
     * </ul>
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribe(
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer afterSeq,
            @RequestParam(required = false) String replyId) {

        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        return tailer.tail(sessionId, replyId, afterSeq != null ? afterSeq : 0);
    }

    // ===== GET /status：查询 turn 状态 =====

    /**
     * 查询 session 当前 turn 状态（前端刷新恢复用）。
     *
     * <p>状态判定全部基于 Pod 间共享的 DB 状态（turn_lease / confirm_context /
     * session_event），不使用任何进程内状态——跨副本部署下本 Pod 可能从未执行过该
     * session，也可能残留过期的本地 sink。
     *
     * <p>响应示例：
     * <pre>{@code
     * {
     *   "session_id": "...",
     *   "state": "working",          // working / completed / waiting_confirm / interrupted / idle
     *   "latest_event_seq": 42,
     *   "reply_id": "...",
     *   "pending_confirm": null      // 或 HITL 确认上下文
     * }
     * }</pre>
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status(@PathVariable String sessionId) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        var pendingConfirm = runtimeService.findPendingConfirm(sessionId);
        var latestEvent = eventStore.findLatest(sessionId);
        var probe = tailer.probe(sessionId, latestEvent, pendingConfirm != null);

        String state;
        if (pendingConfirm != null) {
            state = "waiting_confirm";
        } else if (probe.running()) {
            state = "working";
        } else if (latestEvent != null && "AGENT_END".equals(latestEvent.type())) {
            state = "completed";
        } else if (probe.interrupted()) {
            // 有事件、非终态、无租约、无待确认：执行副本崩溃或被抢占
            state = "interrupted";
        } else {
            state = "idle";
        }

        return Map.of(
            "session_id", sessionId,
            "state", state,
            "latest_event_seq", latestEvent != null ? latestEvent.seq() : 0,
            "reply_id", latestEvent != null && latestEvent.replyId() != null
                ? latestEvent.replyId() : "",
            "pending_confirm", pendingConfirm != null ? pendingConfirm : ""
        );
    }
}
