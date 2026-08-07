package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import reactor.core.publisher.Flux;

@RestController
public class StreamController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        String payload = "{\"type\":\"error\",\"error\":" + jsonEsc(msg) + "}";
        return ServerSentEvent.<String>builder().data(payload).build();
    }

    private String jsonEsc(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            return "\"error\"";
        }
    }

    private ServerSentEvent<String> toSSE(AgentEvent event) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", event.getType().name());
        payload.put("id", event.getId());

        if (event instanceof TextBlockDeltaEvent delta) {
            payload.put("delta", delta.getDelta());
        } else if (event instanceof ToolCallStartEvent tc) {
            payload.put("toolName", tc.getToolCallName());
            payload.put("toolCallId", tc.getToolCallId());
        } else if (event instanceof ToolResultEndEvent tr) {
            payload.put("state", tr.getState().name());
        }

        try {
            return ServerSentEvent.<String>builder()
                .data(MAPPER.writeValueAsString(payload))
                .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().data("{}").build();
        }
    }
}
