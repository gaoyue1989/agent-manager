package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.TransportProtocol;
import io.a2a.util.Utils;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import reactor.core.publisher.Flux;

/**
 * A2A JSON-RPC 控制器（官方 agentscope-a2a-spring-boot-starter 同款实现）。
 *
 * <p>全量透传给 AgentScopeA2aServer（SDK）处理：
 * message/send、message/stream、tasks/get、tasks/cancel、tasks/resubscribe
 * 等所有标准 A2A 方法均由 SDK 的 JsonRpcTransportWrapper 分发。
 *
 * <p>唯一保留的兼容逻辑：message/send 时对旧格式客户端补全 SDK 反序列化必需的字段
 * （message.kind/messageId、parts.kind、blocking=true），
 * 使不按 A2A 规范构造请求的客户端也能正常工作。
 */
@RestController
@RequestMapping("/")
public class A2AController {

    private static final Logger log = LoggerFactory.getLogger(A2AController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentScopeA2aServer agentScopeA2aServer;

    private JsonRpcTransportWrapper jsonRpcHandler;

    public A2AController(AgentScopeA2aServer agentScopeA2aServer) {
        this.agentScopeA2aServer = agentScopeA2aServer;
    }

    @PostMapping(value = "",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object handleRequest(
            @RequestBody String body,
            @RequestHeader Map<String, String> header) {
        // message/send 兼容转换：SDK 反序列化要求 kind/messageId/parts.kind，旧客户端缺失时补全
        var converted = normalizeMessageSendBody(body);
        Object result = getJsonRpcHandler().handleRequest(converted, header, Map.of());
        if (result instanceof Flux<?> fluxResult) {
            // 流式: SDK 返回 JSONRPCResponse 流 → 转 SSE
            @SuppressWarnings("unchecked")
            var flux = (Flux<JSONRPCResponse<?>>) fluxResult;
            return flux.map(this::convertToSse);
        }
        return result;
    }

    /**
     * message/send 与 message/stream 兼容转换：
     * 1. message.kind = "message"（EventKind 多态类型标识，缺失则 -32602）
     * 2. message.messageId 必填（缺失则 -32602）
     * 3. parts 中每个 part 需带 kind 类型标识（如 {"kind":"text","text":"..."}，缺失则 -32602）
     * 4. configuration.blocking=true（仅 message/send 同步场景）
     * 5. 顶层 userId/sessionId → message.metadata（SDK 从 message.metadata 读取）
     * 6. 兼容旧格式 params.metadata.thread_id → message.metadata.sessionId
     */
    private String normalizeMessageSendBody(String body) {
        try {
            @SuppressWarnings("unchecked")
            var req = MAPPER.readValue(body, Map.class);
            var method = (String) req.get("method");
            if (!"message/send".equals(method) && !"message/stream".equals(method)) {
                return body;
            }
            @SuppressWarnings("unchecked")
            var params = (Map<String, Object>) req.get("params");
            if (params == null) {
                return body;
            }
            @SuppressWarnings("unchecked")
            var message = (Map<String, Object>) params.get("message");
            if (message == null) {
                return body;
            }

            // 1/2. message.kind + messageId
            message.putIfAbsent("kind", "message");
            message.putIfAbsent("messageId", java.util.UUID.randomUUID().toString());

            // 3. parts 补 kind 标识
            if (message.get("parts") instanceof List<?> partsList) {
                for (var p : partsList) {
                    if (p instanceof Map<?, ?> partMap) {
                        @SuppressWarnings("unchecked")
                        var part = (Map<String, Object>) partMap;
                        part.putIfAbsent("kind", "text");
                    }
                }
            }

            // 4. blocking=true（message/send 保持同步语义；message/stream 无影响）
            var configuration = new java.util.LinkedHashMap<String, Object>();
            if (params.get("configuration") instanceof Map<?, ?> existing) {
                @SuppressWarnings("unchecked")
                var existingMap = (Map<String, Object>) existing;
                configuration.putAll(existingMap);
            }
            configuration.put("blocking", true);
            params.put("configuration", configuration);

            // 5. 顶层 userId/sessionId → message.metadata
            @SuppressWarnings("unchecked")
            var msgMetadata = (Map<String, Object>) message.get("metadata");
            var meta = msgMetadata != null
                ? new java.util.LinkedHashMap<>(msgMetadata)
                : new java.util.LinkedHashMap<String, Object>();
            Object topLevelUserId = params.get("userId");
            if (topLevelUserId != null && !topLevelUserId.toString().isBlank()) {
                meta.put("userId", topLevelUserId.toString());
            }
            Object topLevelSessionId = params.get("sessionId");
            if (topLevelSessionId != null && !topLevelSessionId.toString().isBlank()) {
                meta.put("sessionId", topLevelSessionId.toString());
            }
            // 6. 兼容旧格式 params.metadata.thread_id → message.metadata.sessionId
            @SuppressWarnings("unchecked")
            var legacyMeta = (Map<String, Object>) params.get("metadata");
            if (legacyMeta != null && !meta.containsKey("sessionId")) {
                var tid = legacyMeta.get("thread_id");
                if (tid != null && !tid.toString().isBlank()) {
                    meta.put("sessionId", tid.toString());
                }
                var uid = legacyMeta.get("userId");
                if (uid != null && !meta.containsKey("userId")) {
                    meta.put("userId", uid.toString());
                }
            }
            message.put("metadata", meta);

            return MAPPER.writeValueAsString(req);
        } catch (Exception e) {
            log.warn("normalizeMessageSendBody failed, use original body: {}", e.getMessage());
            return body;
        }
    }

    private JsonRpcTransportWrapper getJsonRpcHandler() {
        if (jsonRpcHandler == null) {
            jsonRpcHandler = agentScopeA2aServer.getTransportWrapper(
                TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class);
        }
        return jsonRpcHandler;
    }

    private ServerSentEvent<String> convertToSse(JSONRPCResponse<?> response) {
        try {
            String data = Utils.OBJECT_MAPPER.writeValueAsString(response);
            var builder = ServerSentEvent.<String>builder().data(data).event("jsonrpc");
            if (response.getId() != null) {
                builder.id(response.getId().toString());
            }
            return builder.build();
        } catch (Exception e) {
            log.error("Error converting response to SSE: {}", e.getMessage());
            return ServerSentEvent.<String>builder()
                .data("{\"error\":\"Internal conversion error\"}")
                .event("error")
                .build();
        }
    }
}
