package io.agentmanager.framework.controller;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.service.AgentRuntimeService;

@RestController
public class A2AController {
    private static final Logger log = LoggerFactory.getLogger(A2AController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentRuntimeService agentRuntime;

    public A2AController(AgentRuntimeService agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @PostMapping("/")
    public Object handleA2A(
            @RequestBody String body,
            @RequestHeader HttpHeaders headers,
            HttpServletResponse response) {
        try {
            @SuppressWarnings("unchecked")
            var req = MAPPER.readValue(body, Map.class);
            var method = (String) req.get("method");
            var id = req.get("id");

            if (method == null) {
                return jsonRpcError(id, -32600, "Missing method");
            }
            if ("message/send".equals(method)) {
                return handleMessageSend(id, req);
            }
            if ("message/stream".equals(method)) {
                handleStreaming(req, response);
                return null;
            }
            return jsonRpcError(id, -32601, "Method not found: " + method);
        } catch (Exception e) {
            log.error("A2A request failed: {}", e.getMessage(), e);
            return jsonRpcError(null, -32603, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleStreaming(Map<String, Object> req, HttpServletResponse response) throws Exception {
        var params = (Map<String, Object>) req.get("params");
        if (params == null) {
            writeJsonError(response, -32602, "Missing params");
            return;
        }
        var message = (Map<String, Object>) params.get("message");
        if (message == null) {
            writeJsonError(response, -32602, "Missing message in params");
            return;
        }

        var userMessage = extractUserText(message);

        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        var os = response.getOutputStream();
        try {
            agentRuntime.invokeStream(userMessage, null)
                .doOnNext(data -> writeSSE(os, data))
                .doOnComplete(() -> {
                    try {
                        os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (Exception ignored) {}
                })
                .doOnError(e -> {
                    try {
                        var err = MAPPER.writeValueAsString(Map.of("type", "error", "error", e.getMessage()));
                        os.write(("data: " + err + "\n\n").getBytes(StandardCharsets.UTF_8));
                        os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (Exception ignored) {}
                })
                .blockLast();
        } catch (Exception e) {
            log.error("Stream error: {}", e.getMessage(), e);
            try {
                var err = MAPPER.writeValueAsString(Map.of("type", "error", "error", e.getMessage()));
                os.write(("data: " + err + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception ignored) {}
        }
    }

    private void writeJsonError(HttpServletResponse response, int code, String message) throws Exception {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var err = jsonRpcBody(null, code, message);
        response.getWriter().write(MAPPER.writeValueAsString(err));
    }

    private void writeSSE(OutputStream os, Map<String, Object> data) {
        try {
            var json = MAPPER.writeValueAsString(data);
            os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Object> handleMessageSend(Object id, Map<String, Object> req) {
        var params = (Map<String, Object>) req.get("params");
        if (params == null) {
            return jsonRpcError(id, -32602, "Missing params");
        }
        var message = (Map<String, Object>) params.get("message");
        if (message == null) {
            return jsonRpcError(id, -32602, "Missing message in params");
        }

        var userMessage = extractUserText(message);
        var taskId = message.getOrDefault("taskId", UUID.randomUUID().toString()).toString();

        var result = agentRuntime.invoke(userMessage, taskId);
        var responseText = (String) result.getOrDefault("response", "");

        var responseMessage = new LinkedHashMap<String, Object>();
        responseMessage.put("kind", "message");
        responseMessage.put("role", "agent");
        responseMessage.put("messageId", UUID.randomUUID().toString());
        responseMessage.put("parts", List.of(Map.of("kind", "text", "text", responseText)));

        var task = Map.of(
            "id", taskId,
            "status", "completed",
            "result", Map.of("message", responseMessage)
        );

        var resp = new LinkedHashMap<String, Object>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", task);
        return ResponseEntity.ok().body(resp);
    }

    @SuppressWarnings("unchecked")
    private String extractUserText(Map<String, Object> message) {
        var parts = (List<Map<String, Object>>) message.get("parts");
        if (parts == null || parts.isEmpty()) return "";

        var text = new StringBuilder();
        for (var part : parts) {
            var kind = (String) part.get("kind");
            if (kind == null || "text".equals(kind)) {
                var t = part.getOrDefault("text", "").toString();
                if (!t.isBlank()) text.append(t);
            }
        }
        return text.toString().strip();
    }

    private ResponseEntity<Object> jsonRpcError(Object id, int code, String message) {
        return ResponseEntity.ok().body(jsonRpcBody(id, code, message));
    }

    private Map<String, Object> jsonRpcBody(Object id, int code, String message) {
        var err = new LinkedHashMap<String, Object>();
        err.put("jsonrpc", "2.0");
        err.put("id", id);
        err.put("error", Map.of("code", code, "message", message));
        return err;
    }
}
