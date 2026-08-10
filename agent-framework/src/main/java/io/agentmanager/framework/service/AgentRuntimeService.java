package io.agentmanager.framework.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.UserMessage;
import reactor.core.publisher.Flux;

public class AgentRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);

    private final OafConfig oafConfig;
    private final String tenantPrefix;
    private final LLMLogger llmLogger;

    private io.agentscope.harness.agent.HarnessAgent agent;
    private final List<Map<String, Object>> mcpConfigs;

    public AgentRuntimeService(
        OafConfig oafConfig,
        io.agentscope.harness.agent.HarnessAgent agent,
        List<Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger
    ) {
        this.oafConfig = oafConfig;
        this.tenantPrefix = oafConfig.slug();
        this.agent = agent;
        this.mcpConfigs = mcpConfigs;
        this.llmLogger = llmLogger;
    }

    public String tenantPrefix() { return tenantPrefix; }
    public String name() { return oafConfig.name(); }
    public String description() { return oafConfig.description(); }
    public OafConfig oafConfig() { return oafConfig; }

    public String buildSystemPrompt() {
        var sb = new StringBuilder(oafConfig.systemPrompt());
        if (!mcpConfigs.isEmpty()) {
            sb.append("\n\n## Available MCP Servers\n");
            for (var mc : mcpConfigs) {
                @SuppressWarnings("unchecked")
                var tools = (Map<String, Object>) mc.getOrDefault("tools", Map.of());
                @SuppressWarnings("unchecked")
                var selected = (List<Map<String, Object>>) tools.getOrDefault("selectedTools", List.of());
                var toolNames = selected.stream()
                    .filter(t -> (boolean) t.getOrDefault("enabled", true))
                    .map(t -> (String) t.get("name"))
                    .toList();
                sb.append("- **").append(mc.getOrDefault("server", "unknown")).append("** (")
                  .append(toolNames.size()).append(" tools: ")
                  .append(String.join(", ", toolNames.subList(0, Math.min(10, toolNames.size()))))
                  .append(")\n");
            }
        }
        return sb.toString();
    }

    public List<String> toolsList() {
        return oafConfig.tools();
    }

    private String makeThreadId(String threadId) {
        // AgentStateStore ID 不允许包含路径分隔符，替换 slug 中的 "/"
        return tenantPrefix.replace("/", "-") + ":" + threadId;
    }

    private String resolveUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return oafConfig.vendorKey();
        }
        return userId;
    }

    public Map<String, Object> invoke(String message, String threadId) {
        return invoke(message, threadId, oafConfig.vendorKey());
    }

    /**
     * 同步调用，支持多租户：userId 由调用方显式传递。
     */
    public Map<String, Object> invoke(String message, String threadId, String userId) {
        if (threadId == null || threadId.isEmpty()) {
            threadId = UUID.randomUUID().toString();
        }
        var fullThreadId = makeThreadId(threadId);
        var resolvedUserId = resolveUserId(userId);

        try {
            var ctx = RuntimeContext.builder()
                .sessionId(fullThreadId)
                .userId(resolvedUserId)
                .build();

            var userMsg = new UserMessage("user", message);
            var result = agent.call(List.of(userMsg), ctx).block();

            var responseText = result != null ? result.getTextContent() : "";
            return Map.of("response", responseText, "thread_id", threadId);
        } catch (Exception e) {
            log.error("invoke failed: {}", e.getMessage(), e);
            return Map.of("response", "[Agent:" + name() + "] Error: " + e.getMessage(), "thread_id", threadId);
        }
    }

    public Flux<Map<String, Object>> invokeStream(String message, String threadId) {
        return invokeStream(message, threadId, oafConfig.vendorKey());
    }

    /**
     * 流式调用，支持多租户：userId 由调用方显式传递。
     */
    public Flux<Map<String, Object>> invokeStream(String message, String threadId, String userId) {
        var tid = threadId != null && !threadId.isEmpty() ? threadId : UUID.randomUUID().toString();
        var fullThreadId = makeThreadId(tid);
        var resolvedUserId = resolveUserId(userId);
        var ctx = RuntimeContext.builder()
            .sessionId(fullThreadId)
            .userId(resolvedUserId)
            .build();

        var userMsg = new UserMessage("user", message);

        return Flux.create(sink -> {
            sink.next(Map.of("type", "task_update", "id", tid, "state", "working"));

            agent.streamEvents(List.of(userMsg), ctx)
                .doOnNext(event -> {
                    var type = event.getType();

                    // ===== 生命周期事件 =====
                    if (type == AgentEventType.AGENT_START) {
                        var e = (io.agentscope.core.event.AgentStartEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "agent_start");
                        m.put("task_id", tid);
                        putIfNotNull(m, "reply_id", e.getReplyId());
                        putIfNotNull(m, "session_id", e.getSessionId());
                        putIfNotNull(m, "name", e.getName());
                        putIfNotNull(m, "role", e.getRole());
                        sink.next(m);
                    }

                    // ===== 文本流式事件 =====
                    else if (type == AgentEventType.TEXT_BLOCK_START) {
                        var e = (io.agentscope.core.event.TextBlockStartEvent) event;
                        sink.next(blockEvent("text_block_start", tid, e.getReplyId(), e.getBlockId()));
                    }
                    else if (type == AgentEventType.TEXT_BLOCK_DELTA) {
                        var e = (io.agentscope.core.event.TextBlockDeltaEvent) event;
                        sink.next(Map.of("type", "token", "token", e.getDelta(), "task_id", tid));
                    }
                    else if (type == AgentEventType.TEXT_BLOCK_END) {
                        var e = (io.agentscope.core.event.TextBlockEndEvent) event;
                        sink.next(blockEvent("text_block_end", tid, e.getReplyId(), e.getBlockId()));
                    }

                    // ===== 思维链事件 =====
                    else if (type == AgentEventType.THINKING_BLOCK_START) {
                        var e = (io.agentscope.core.event.ThinkingBlockStartEvent) event;
                        sink.next(blockEvent("thinking_block_start", tid, e.getReplyId(), e.getBlockId()));
                    }
                    else if (type == AgentEventType.THINKING_BLOCK_DELTA) {
                        var e = (io.agentscope.core.event.ThinkingBlockDeltaEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "thinking_block_delta");
                        m.put("task_id", tid);
                        putIfNotNull(m, "delta", e.getDelta());
                        putIfNotNull(m, "reply_id", e.getReplyId());
                        putIfNotNull(m, "block_id", e.getBlockId());
                        sink.next(m);
                    }
                    else if (type == AgentEventType.THINKING_BLOCK_END) {
                        var e = (io.agentscope.core.event.ThinkingBlockEndEvent) event;
                        sink.next(blockEvent("thinking_block_end", tid, e.getReplyId(), e.getBlockId()));
                    }

                    // ===== 多模态数据事件 =====
                    else if (type == AgentEventType.DATA_BLOCK_START) {
                        var e = (io.agentscope.core.event.DataBlockStartEvent) event;
                        sink.next(blockEvent("data_block_start", tid, e.getReplyId(), e.getBlockId()));
                    }
                    else if (type == AgentEventType.DATA_BLOCK_DELTA) {
                        var e = (io.agentscope.core.event.DataBlockDeltaEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "data_block_delta");
                        m.put("task_id", tid);
                        putIfNotNull(m, "delta", e.getDelta());
                        putIfNotNull(m, "reply_id", e.getReplyId());
                        putIfNotNull(m, "block_id", e.getBlockId());
                        sink.next(m);
                    }
                    else if (type == AgentEventType.DATA_BLOCK_END) {
                        var e = (io.agentscope.core.event.DataBlockEndEvent) event;
                        sink.next(blockEvent("data_block_end", tid, e.getReplyId(), e.getBlockId()));
                    }

                    // ===== 工具调用流式事件 =====
                    else if (type == AgentEventType.TOOL_CALL_START) {
                        var tc = (io.agentscope.core.event.ToolCallStartEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "tool_call");
                        m.put("task_id", tid);
                        putIfNotNull(m, "name", tc.getToolCallName());
                        putIfNotNull(m, "tool_call_id", tc.getToolCallId());
                        putIfNotNull(m, "reply_id", tc.getReplyId());
                        sink.next(m);
                    }
                    else if (type == AgentEventType.TOOL_CALL_DELTA) {
                        var e = (io.agentscope.core.event.ToolCallDeltaEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "tool_call_delta");
                        m.put("task_id", tid);
                        putIfNotNull(m, "delta", e.getDelta());
                        putIfNotNull(m, "tool_call_id", e.getToolCallId());
                        putIfNotNull(m, "tool_call_name", e.getToolCallName());
                        sink.next(m);
                    }
                    else if (type == AgentEventType.TOOL_CALL_END) {
                        var e = (io.agentscope.core.event.ToolCallEndEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "tool_call_end");
                        m.put("task_id", tid);
                        putIfNotNull(m, "tool_call_id", e.getToolCallId());
                        putIfNotNull(m, "tool_call_name", e.getToolCallName());
                        sink.next(m);
                    }

                    // ===== 工具结果流式事件 =====
                    else if (type == AgentEventType.TOOL_RESULT_START) {
                        var e = (io.agentscope.core.event.ToolResultStartEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "tool_result_start");
                        m.put("task_id", tid);
                        putIfNotNull(m, "tool_call_id", e.getToolCallId());
                        putIfNotNull(m, "tool_call_name", e.getToolCallName());
                        sink.next(m);
                    }
                    else if (type == AgentEventType.TOOL_RESULT_TEXT_DELTA) {
                        var e = (io.agentscope.core.event.ToolResultTextDeltaEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "tool_result_text_delta");
                        m.put("task_id", tid);
                        putIfNotNull(m, "delta", e.getDelta());
                        putIfNotNull(m, "tool_call_id", e.getToolCallId());
                        putIfNotNull(m, "tool_call_name", e.getToolCallName());
                        sink.next(m);
                    }
                    else if (type == AgentEventType.TOOL_RESULT_DATA_DELTA) {
                        var e = (io.agentscope.core.event.ToolResultDataDeltaEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "tool_result_data_delta");
                        m.put("task_id", tid);
                        putIfNotNull(m, "tool_call_id", e.getToolCallId());
                        putIfNotNull(m, "tool_call_name", e.getToolCallName());
                        // ContentBlock 可能是 DataBlock，提取 source (Base64Source / URLSource)
                        if (e.getData() instanceof io.agentscope.core.message.DataBlock dataBlock) {
                            var source = dataBlock.getSource();
                            if (source instanceof io.agentscope.core.message.Base64Source base64) {
                                putIfNotNull(m, "media_type", base64.getMediaType());
                                putIfNotNull(m, "data", base64.getData());
                            } else if (source instanceof io.agentscope.core.message.URLSource urlSource) {
                                putIfNotNull(m, "media_type", urlSource.getMimeType());
                                putIfNotNull(m, "url", urlSource.getUrl());
                            }
                        }
                        sink.next(m);
                    }
                    else if (type == AgentEventType.TOOL_RESULT_END) {
                        var tr = (io.agentscope.core.event.ToolResultEndEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "tool_result");
                        m.put("task_id", tid);
                        m.put("state", tr.getState().name());
                        putIfNotNull(m, "tool_call_id", tr.getToolCallId());
                        putIfNotNull(m, "tool_call_name", tr.getToolCallName());
                        sink.next(m);
                    }

                    // ===== 模型调用事件 =====
                    else if (type == AgentEventType.MODEL_CALL_START) {
                        var e = (io.agentscope.core.event.ModelCallStartEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "model_call_start");
                        m.put("task_id", tid);
                        putIfNotNull(m, "reply_id", e.getReplyId());
                        sink.next(m);
                    }
                    else if (type == AgentEventType.MODEL_CALL_END) {
                        var e = (io.agentscope.core.event.ModelCallEndEvent) event;
                        var m = new LinkedHashMap<String, Object>();
                        m.put("type", "model_call_end");
                        m.put("task_id", tid);
                        if (e.getUsage() != null) {
                            m.put("input_tokens", e.getUsage().getInputTokens());
                            m.put("output_tokens", e.getUsage().getOutputTokens());
                            m.put("total_tokens", e.getUsage().getTotalTokens());
                        }
                        sink.next(m);
                    }

                    // ===== 结束事件 =====
                    else if (type == AgentEventType.AGENT_END) {
                        sink.next(Map.of(
                            "type", "task_update", "id", tid,
                            "state", "completed",
                            "metadata", Map.of("thread_id", tid)
                        ));
                        sink.next(Map.of("type", "done"));
                        sink.complete();
                    }
                })
                .doOnError(e -> {
                    log.error("stream error: {}", e.getMessage(), e);
                    sink.next(Map.of("type", "error", "task_id", tid, "error", e.getMessage()));
                    sink.next(Map.of("type", "done"));
                    sink.complete();
                })
                .doOnComplete(() -> {
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                })
                .subscribe();
        });
    }

    public void setAgent(io.agentscope.harness.agent.HarnessAgent agent) {
        this.agent = agent;
    }

    /** 仅当 value 非 null 时写入 map，避免 Map.of 抛 NPE */
    private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    /** 构造带 reply_id/block_id 的块级事件 Map */
    private static Map<String, Object> blockEvent(String type, String tid, String replyId, String blockId) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("task_id", tid);
        putIfNotNull(m, "reply_id", replyId);
        putIfNotNull(m, "block_id", blockId);
        return m;
    }
}
