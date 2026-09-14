package io.agentmanager.framework.controller;

import java.util.ArrayList;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.UiContextStore;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;

/**
 * @deprecated 使用 {@link SessionStreamController} 替代（DURABLE_SSE 架构，支持心跳、断连续传、turn 租约）。
 *             前端已迁移至 POST /threads/{sid}/chat，此端点仅保留向后兼容。
 */
@Deprecated
@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    private final ChatUiChannel chatChannel;
    private final McpToolRegistrar mcpToolRegistrar;
    private final SessionUserStore sessionUserStore;

    public StreamController(ChatUiChannel chatChannel,
                            McpToolRegistrar mcpToolRegistrar,
                            SessionUserStore sessionUserStore) {
        this.chatChannel = chatChannel;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.sessionUserStore = sessionUserStore;
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestParam String message,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String subagentId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        // 网关 Header 优先 > 请求参数 > 默认 debug-user
        if (headerUserId != null && !headerUserId.isBlank()) {
            userId = headerUserId;
        } else if (userId == null || userId.isBlank()) {
            userId = "debug-user";
        }

        if (subagentId != null && !subagentId.isBlank()) {
            return chatChannel.sendToSubagentStream(subagentId, message)
                .map(this::toSSE)
                .onErrorResume(e -> Flux.just(errorSSE(e)));
        }

        // ★ Windows 路径安全化：SDK 把 peer/sessionId 当目录名用
        userId = io.agentmanager.framework.util.PathSafe.sanitize(userId);
        sessionId = sessionId != null && !sessionId.isBlank()
            ? io.agentmanager.framework.util.PathSafe.sanitize(sessionId) : null;

        // 会话 key 与 SendOptions 语义一致：sessionId 优先，缺省回落 userId
        var peerId = sessionId != null && !sessionId.isBlank() ? sessionId : userId;

        // 记录会话-用户映射，供 GET /threads?userId=xxx 过滤
        sessionUserStore.upsert(peerId, userId);

        // UI 交互上下文（4.7）：会话 key 写入用户消息 metadata，UiContextInjectionHook
        // 在 PreCallEvent 阶段注入（HarnessAgent 拒绝 inputMessages 中 SYSTEM 消息）
        var messages = new ArrayList<Msg>();
        if (sessionId != null && !sessionId.isBlank()) {
            messages.add(Msg.builder().role(MsgRole.USER).name(userId)
                .metadata(Map.of(UiContextStore.METADATA_SESSION_KEY, sessionId))
                .textContent(message).build());
        } else {
            messages.add(Msg.builder().role(MsgRole.USER).name(userId).textContent(message).build());
        }

        return chatChannel.sendStream(ChatUiRequest.withPeer(peerId, messages))
            .map(this::toSSE)
            .onErrorResume(e -> Flux.just(errorSSE(e)));
    }

    private ServerSentEvent<String> errorSSE(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String payload = "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(msg) + "}";
        return ServerSentEvent.<String>builder().data(payload).build();
    }

    private ServerSentEvent<String> toSSE(AgentEvent event) {
        String data;
        if (event instanceof ToolCallStartEvent tc) {
            var uiRef = mcpToolRegistrar.resolveUiRef(tc.getToolCallName());
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
}