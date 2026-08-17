package io.agentmanager.framework.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import reactor.core.publisher.Flux;

@RestController
public class StreamController {

    private final ChatUiChannel chatChannel;

    public StreamController(ChatUiChannel chatChannel) {
        this.chatChannel = chatChannel;
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

        SendOptions options = sessionId != null && !sessionId.isBlank()
            ? SendOptions.of(userId, sessionId)
            : SendOptions.userId(userId);

        return chatChannel.sendStream(options, message)
            .map(this::toSSE)
            .onErrorResume(e -> Flux.just(errorSSE(e)));
    }

    private ServerSentEvent<String> errorSSE(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String payload = "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(msg) + "}";
        return ServerSentEvent.<String>builder().data(payload).build();
    }

    private ServerSentEvent<String> toSSE(AgentEvent event) {
        return ServerSentEvent.<String>builder()
            .data(AgentEventSseSerializer.payload(event))
            .build();
    }
}