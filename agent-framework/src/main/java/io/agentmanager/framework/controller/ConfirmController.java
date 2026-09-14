package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.TurnLeaseGuard;
import io.agentmanager.framework.service.TurnLeaseStore;
import reactor.core.publisher.Flux;

/**
 * HITL 确认端点（业务能力，独立于 Debug 页面，见 docs/hitl-permission-plan.md 6.3）。
 *
 * <ul>
 *   <li>POST /threads/{sessionId}/confirm —— 携带确认决策恢复 agent，同步返回恢复执行后的最终回复</li>
 *   <li>POST /threads/{sessionId}/confirm-stream —— 恢复执行的事件流式下发（DURABLE_SSE：事件经 EventBus
 *       持久化 + 广播，SSE 断连后可通过 GET /subscribe 重连续传）</li>
 * </ul>
 *
 * <p>错误码（12.5）：缓存 miss → 404 {@code confirm_context_not_found}；重复确认（CAS 防护）→ 409
 * {@code confirm_already_consumed}；confirm-stream 预检失败以 error SSE 帧返回。
 *
 * <p>执行权语义：confirm 恢复 = 新执行段，confirm-stream 需先 acquire turn 租约；permission_ask
 * 暂停点锁已让出，此处通常可直接抢到；抢不到（并发确认/新消息正在执行）→ error 帧 turn_in_progress。
 */
@RestController
@RequestMapping("/threads/{sessionId}")
public class ConfirmController {

    private static final Logger log = LoggerFactory.getLogger(ConfirmController.class);

    private final AgentRuntimeService runtimeService;
    private final TurnLeaseStore turnLeaseStore;
    private final SessionEventBus eventBus;
    private final SessionUserStore sessionUserStore;

    public ConfirmController(AgentRuntimeService runtimeService,
                             TurnLeaseStore turnLeaseStore,
                             SessionEventBus eventBus,
                             SessionUserStore sessionUserStore) {
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
        this.eventBus = eventBus;
        this.sessionUserStore = sessionUserStore;
    }

    /** 同步版：恢复 agent 执行，返回最终回复（无状态架构：无事件扇出，调用方直接消费结果） */
    @PostMapping(value = "/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String sessionId, @RequestBody ConfirmRequest body) {
        // ★ Windows 路径安全化：与 SessionStreamController 保持一致
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);

        // 恢复确认时刷新会话-用户映射（确认恢复可能是新的入口，确保映射存在）
        var userId = sessionUserStore.findUserIdBySession(sessionId);
        if (userId != null) {
            sessionUserStore.upsert(sessionId, userId);
        }

        try {
            var result = runtimeService.resumeWithConfirm(sessionId, null, body.results());
            return ResponseEntity.ok(result);
        } catch (AgentRuntimeService.ConfirmContextNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "confirm_context_not_found",
                "message", "Session not found or confirm context expired"));
        } catch (AgentRuntimeService.ConfirmAlreadyConsumedException e) {
            return ResponseEntity.status(409).body(Map.of(
                "error", "confirm_already_consumed",
                "message", "This confirm has already been processed"));
        }
    }

    /**
     * 流式版：确认后事件流（DURABLE_SSE 架构）。
     *
     * <p>与 SessionStreamController.chat 相同的模式：
     * <ol>
     *   <li>acquire turn 租约</li>
     *   <li>ensureSink — 确保 EventBus 有输出通道</li>
     *   <li>先订阅 EventBus → SSE（避免与 agent 执行的竞态）</li>
     *   <li>启动 agent 恢复执行 → 事件写入 EventBus</li>
     *   <li>onCancel 仅取消 SSE 订阅，不 dispose agent 管道</li>
     * </ol>
     */
    @PostMapping(value = "/confirm-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> confirmStream(
            @PathVariable String sessionId, @RequestBody ConfirmRequest body) {
        // ★ Windows 路径安全化：与 SessionStreamController 保持一致
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        String finalSessionId = sessionId;

        // 恢复确认时刷新会话-用户映射
        var confirmUserId = sessionUserStore.findUserIdBySession(finalSessionId);
        if (confirmUserId != null) {
            sessionUserStore.upsert(finalSessionId, confirmUserId);
        }

        return Flux.<ServerSentEvent<String>>create(sink -> {
            // ===== 1. 预检查确认可用性 =====
            try {
                runtimeService.checkConfirmAvailable(finalSessionId);
            } catch (Exception e) {
                sink.next(errorSSE(e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName()));
                sink.complete();
                return;
            }

            // ===== 2. 抢 Turn 租约 =====
            var token = turnLeaseStore.tryAcquire(finalSessionId);
            if (token == null) {
                sink.next(errorSSE("turn_in_progress: session '" + finalSessionId
                    + "' has an active turn"));
                sink.complete();
                return;
            }
            TurnLeaseGuard lease = new TurnLeaseGuard(turnLeaseStore, finalSessionId, token);

            // ===== 3. 准备 EventBus Sinks =====
            String replyId = UUID.randomUUID().toString();
            eventBus.ensureSink(finalSessionId);

            // ===== 4. 先订阅 EventBus → SSE =====
            eventBus.subscribe(finalSessionId, 0, replyId)
                .subscribe(
                    sse -> sink.next(sse),
                    e -> {
                        log.warn("EventBus subscription error on confirm-stream (sid={}): {}",
                            finalSessionId, e.getMessage());
                        sink.error(e);
                    },
                    () -> sink.complete());

            // ===== 5. 启动 agent 恢复执行 → 事件写入 EventBus =====
            runtimeService.resumeWithConfirmEvents(finalSessionId, null, body.results())
                .subscribe(
                    event -> handleEventAndEmit(event, finalSessionId, replyId, lease),
                    e -> {
                        log.warn("confirm-stream agent error (sid={}): {}",
                            finalSessionId, e.getMessage());
                        eventBus.emitSynthetic(finalSessionId, replyId, "error",
                            "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "}");
                        lease.release();
                        eventBus.closeSession(finalSessionId);
                    },
                    () -> {
                        // 正常完成：幂等兜底（AGENT_END 已在 handleEventAndEmit 中释放租约 + 关闭 session）
                        lease.release();
                        eventBus.closeSession(finalSessionId);
                    });

            // ===== 6. onCancel：仅取消 SSE 订阅，不 dispose agent 管道 =====
            sink.onCancel(() -> {
                log.info("SSE disconnected, agent execution continues on confirm-stream (sid={}, rid={})",
                    finalSessionId, replyId);
            });

        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // ===== 事件处理（写入 EventBus） =====

    /** 单帧处理：HITL 落库 + 事件写入 EventBus + 终态关闭 */
    private void handleEventAndEmit(io.agentscope.core.event.AgentEvent event,
                                    String sessionId, String replyId, TurnLeaseGuard lease) {
        // Channel 流程 HITL：permission_ask → 上下文落库 + 释放租约（执行段结束，锁让出）
        if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent) {
            runtimeService.storeConfirmContext(sessionId, event);
            lease.release();
            // HITL 暂停点：锁已让出、状态已持久化
        }

        // ★ 核心变化：事件写入 EventBus（而非直接写入 FluxSink）
        eventBus.emit(sessionId, event, replyId);

        // AGENT_END → 关闭 EventBus
        if (event.getType() == io.agentscope.core.event.AgentEventType.AGENT_END) {
            lease.release();
            eventBus.closeSession(sessionId);
        }
    }

    private static ServerSentEvent<String> errorSSE(String msg) {
        return ServerSentEvent.<String>builder()
            .data(AgentEventSseSerializer.payload(Map.of("type", "error", "error", msg)))
            .build();
    }

    public record ConfirmRequest(List<Map<String, Object>> results) {
    }
}
