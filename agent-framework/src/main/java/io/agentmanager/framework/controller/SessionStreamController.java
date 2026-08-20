package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.UiContextStore;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;

/**
 * 长连接 SSE 会话流端点（debug 页面，计划文档 3.8 / 六-F）。
 *
 * <ul>
 *   <li>GET  /debug/threads/{sessionId}/events —— 订阅会话事件总线（长连接，心跳保活）</li>
 *   <li>POST /debug/threads/{sessionId}/chat  —— fire-and-forget 触发，事件经总线回流到订阅者</li>
 * </ul>
 *
 * <p>对齐官方 agentscope 模式：订阅端点直接开始推送事件，触发端点 fire-and-forget，
 * 事件通过已建立的 SSE 连接回流到前端。</p>
 */
@RestController
@RequestMapping("/debug/threads/{sessionId}")
public class SessionStreamController {

    private static final Logger log = LoggerFactory.getLogger(SessionStreamController.class);
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final ChatUiChannel chatChannel;
    private final SessionEventBus eventBus;
    private final AgentRuntimeService runtimeService;
    private final McpToolRegistrar mcpToolRegistrar;

    public SessionStreamController(ChatUiChannel chatChannel, SessionEventBus eventBus,
                                   AgentRuntimeService runtimeService, McpToolRegistrar mcpToolRegistrar) {
        this.chatChannel = chatChannel;
        this.eventBus = eventBus;
        this.runtimeService = runtimeService;
        this.mcpToolRegistrar = mcpToolRegistrar;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribe(@PathVariable String sessionId) {
        var sink = eventBus.sink(sessionId);
        // 初始帧：确保 Spring 立即发送 HTTP 响应头（否则 fetch() 会等到首个数据才返回）
        var connected = ServerSentEvent.<String>builder()
            .event("connected").data("{}").build();
        return Flux.concat(
                Flux.just(connected),
                sink.asFlux().map(e -> toSSE(e, mcpToolRegistrar)))
            .mergeWith(tick(sessionId))
            .doFinally(sig -> eventBus.onUnsubscribe(sessionId));
    }

    /** 心跳：定时 ping，保持长连接不被断开 */
    private Flux<ServerSentEvent<String>> tick(String sessionId) {
        return Flux.interval(HEARTBEAT).map(i ->
            ServerSentEvent.<String>builder().event("ping").data("{}").build());
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> trigger(@PathVariable String sessionId,
                                       @RequestBody TriggerRequest body) {
        var message = body.message();
        if (message == null || message.isBlank()) {
            return Map.of("accepted", false, "error", "message is required");
        }
        var userId = body.userId() != null ? body.userId() : "debug-user";

        // 消息列表：UI 交互上下文（4.7）经 UiContextInjectionHook 注入——
        // HarnessAgent 拒绝 inputMessages 中 SYSTEM 消息，这里仅把会话 key 写入
        // 用户消息 metadata，Hook 在 PreCallEvent 阶段按会话查库 appendSystemContent
        var messages = new ArrayList<Msg>();
        messages.add(Msg.builder().role(MsgRole.USER).name(userId)
            .metadata(Map.of(UiContextStore.METADATA_SESSION_KEY, sessionId))
            .textContent(message).build());

        // fire-and-forget：订阅触发，事件经事件总线扇出；AGENT_END 为终态标记，前端据此收尾
        // ChatUiRequest.withPeer 的 peerId 即会话 key（= SendOptions.of(u,s).effectiveSessionKey()）
        chatChannel.sendStream(ChatUiRequest.withPeer(sessionId, messages))
            .doOnNext(event -> {
                eventBus.emit(sessionId, event);
                runtimeService.storeConfirmContext(sessionId, event);  // Channel 流程存储确认上下文
            })
            .doOnError(e -> log.warn("session chat stream error (sid={}): {}", sessionId, e.getMessage()))
            .subscribe();

        log.info("Session chat triggered: sid={}, len={}", sessionId, message.length());
        return Map.of("accepted", true, "sessionId", sessionId);
    }

    private static ServerSentEvent<String> toSSE(AgentEvent event, McpToolRegistrar registrar) {
        String data;
        if (event instanceof ToolCallStartEvent tc) {
            // MCP Apps：工具带 ui 元数据时 payload 携带 ui 字段（裸名冲突时 resolveUiRef 返回 null 降级）
            var uiRef = registrar.resolveUiRef(tc.getToolCallName());
            data = uiRef != null
                ? AgentEventSseSerializer.payload(event, uiRef.resourceUri(), uiRef.serverName())
                : AgentEventSseSerializer.payload(event);
        } else {
            data = AgentEventSseSerializer.payload(event);
        }
        return ServerSentEvent.<String>builder()
            .data(data)
            .build();
    }

    /** POST 请求体（受控 F）：message 必填，userId 可选 */
    public record TriggerRequest(String message, String userId) {
    }
}