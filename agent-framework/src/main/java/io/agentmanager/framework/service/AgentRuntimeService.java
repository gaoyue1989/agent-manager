package io.agentmanager.framework.service;

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
                    if (type == AgentEventType.TEXT_BLOCK_DELTA) {
                        var delta = ((io.agentscope.core.event.TextBlockDeltaEvent) event).getDelta();
                        sink.next(Map.of("type", "token", "token", delta, "task_id", tid));
                    } else if (type == AgentEventType.TOOL_CALL_START) {
                        var tc = (io.agentscope.core.event.ToolCallStartEvent) event;
                        sink.next(Map.of(
                            "type", "tool_call", "task_id", tid,
                            "name", tc.getToolCallName(),
                            "tool_call_id", tc.getToolCallId()
                        ));
                    } else if (type == AgentEventType.TOOL_RESULT_END) {
                        var tr = (io.agentscope.core.event.ToolResultEndEvent) event;
                        sink.next(Map.of(
                            "type", "tool_result", "task_id", tid,
                            "state", tr.getState().name()
                        ));
                    } else if (type == AgentEventType.AGENT_END) {
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
}
