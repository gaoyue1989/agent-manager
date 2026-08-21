package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

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
import io.agentmanager.framework.service.TurnLeaseGuard;
import io.agentmanager.framework.service.TurnLeaseStore;
import reactor.core.publisher.Flux;

/**
 * HITL 确认端点（业务能力，独立于 Debug 页面，见 docs/hitl-permission-plan.md 6.3）。
 *
 * <ul>
 *   <li>POST /threads/{sessionId}/confirm —— 携带确认决策恢复 agent，同步返回恢复执行后的最终回复</li>
 *   <li>POST /threads/{sessionId}/confirm-stream —— 恢复执行的事件流式下发（词表与普通流一致）</li>
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

    public ConfirmController(AgentRuntimeService runtimeService, TurnLeaseStore turnLeaseStore) {
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
    }

    /** 同步版：恢复 agent 执行，返回最终回复（无状态架构：无事件扇出，调用方直接消费结果） */
    @PostMapping(value = "/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String sessionId, @RequestBody ConfirmRequest body) {
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

    /** 流式版：确认后事件流（新执行段，先 acquire turn 租约再恢复） */
    @PostMapping(value = "/confirm-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> confirmStream(
            @PathVariable String sessionId, @RequestBody ConfirmRequest body) {
        return Flux.defer(() -> {
            try {
                runtimeService.checkConfirmAvailable(sessionId);
            } catch (Exception e) {
                return Flux.just(errorSSE(e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName()));
            }
            var token = turnLeaseStore.tryAcquire(sessionId);
            if (token == null) {
                // 已有活跃执行段（并发确认 / 新消息正在执行）→ 409 turn_in_progress，以 SSE error 帧表达
                return Flux.just(errorSSE("turn_in_progress: session '" + sessionId
                    + "' has an active turn"));
            }
            var lease = new TurnLeaseGuard(turnLeaseStore, sessionId, token);
            return runtimeService.resumeWithConfirmStream(sessionId, null, body.results())
                .map(m -> ServerSentEvent.<String>builder()
                    .data(AgentEventSseSerializer.payload(m))
                    .build())
                .doFinally(signal -> lease.release());
        });
    }

    private static ServerSentEvent<String> errorSSE(String msg) {
        return ServerSentEvent.<String>builder()
            .data(AgentEventSseSerializer.payload(Map.of("type", "error", "error", msg)))
            .build();
    }

    public record ConfirmRequest(List<Map<String, Object>> results) {
    }
}