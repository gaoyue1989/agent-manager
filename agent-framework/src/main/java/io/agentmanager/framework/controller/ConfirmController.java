package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.AgentRuntimeService;
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
 */
@RestController
@RequestMapping("/threads/{sessionId}")
public class ConfirmController {

    private final AgentRuntimeService runtimeService;

    public ConfirmController(AgentRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /** 同步版：恢复 agent 执行，返回最终回复（长连接场景事件同时经 SessionEventBus 扇出到原订阅） */
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

    /** 流式版：确认后事件流（供单次流/长连接调用方实时消费恢复过程） */
    @PostMapping(value = "/confirm-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> confirmStream(
            @PathVariable String sessionId, @RequestBody ConfirmRequest body) {
        try {
            runtimeService.checkConfirmAvailable(sessionId);
        } catch (Exception e) {
            return Flux.just(ServerSentEvent.<String>builder()
                .data(AgentEventSseSerializer.payload(Map.of("type", "error", "error", e.getMessage())))
                .build());
        }
        return runtimeService.resumeWithConfirmStream(sessionId, null, body.results())
            .map(m -> ServerSentEvent.<String>builder()
                .data(AgentEventSseSerializer.payload(m))
                .build());
    }

    public record ConfirmRequest(List<Map<String, Object>> results) {
    }
}