package io.agentmanager.framework.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import io.agentmanager.framework.service.AgentRuntimeService;
import reactor.core.publisher.Flux;

@RestController
public class StreamController {

    private final AgentRuntimeService agentRuntime;

    public StreamController(AgentRuntimeService agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> chatStream(@RequestBody Map<String, Object> body) {
        var message = body.getOrDefault("message", "").toString();
        var metadata = body.getOrDefault("metadata", Map.of());
        @SuppressWarnings("unchecked")
        var threadId = (String) ((Map<String, Object>) metadata).getOrDefault("thread_id", null);
        return agentRuntime.invokeStream(message, threadId);
    }
}
