package io.agentmanager.framework.controller;

import java.util.ArrayList;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.UiContextStore;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;

@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    private final ChatUiChannel chatChannel;
    private final McpToolRegistrar mcpToolRegistrar;

    public StreamController(ChatUiChannel chatChannel, McpToolRegistrar mcpToolRegistrar) {
        this.chatChannel = chatChannel;
        this.mcpToolRegistrar = mcpToolRegistrar;
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestParam String message,
            @RequestParam String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String subagentId
    ) {
        if (subagentId != null && !subagentId.isBlank()) {
            return chatChannel.sendToSubagentStream(subagentId, message)
                .map(this::toSSE)
                .onErrorResume(e -> Flux.just(errorSSE(e)));
        }

        // 会话 key 与 SendOptions 语义一致：sessionId 优先，缺省回落 userId
        var peerId = sessionId != null && !sessionId.isBlank() ? sessionId : userId;

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