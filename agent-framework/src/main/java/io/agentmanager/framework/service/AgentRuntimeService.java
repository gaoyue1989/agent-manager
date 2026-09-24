package io.agentmanager.framework.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

public class AgentRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);

    private final OafConfig oafConfig;
    private final String tenantPrefix;
    private final LLMLogger llmLogger;
    private final ConfirmContextStore confirmContextStore;
    /**
     * AgentState 读取器：HITL 恢复时从 state 重建 ASKING 的 ToolUseBlock。
     * 可为 null（部分测试按 5 参构造），此时回落 confirm_context 表。
     */
    private AgentStateReader agentStateReader;

    private volatile io.agentscope.harness.agent.HarnessAgent agent;
    private final List<Map<String, Object>> mcpConfigs;

    public AgentRuntimeService(
        OafConfig oafConfig,
        io.agentscope.harness.agent.HarnessAgent agent,
        List<Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger,
        ConfirmContextStore confirmContextStore
    ) {
        this.oafConfig = oafConfig;
        this.tenantPrefix = oafConfig.slug();
        this.agent = agent;
        this.mcpConfigs = mcpConfigs;
        this.llmLogger = llmLogger;
        this.confirmContextStore = confirmContextStore;
    }

    /** 注入 AgentState 读取器（Bean 装配时调用；测试可不注入，回落 confirm_context） */
    public void setAgentStateReader(AgentStateReader agentStateReader) {
        this.agentStateReader = agentStateReader;
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
        // Windows 不允许冒号出现在路径段中，用 "__" 替代 ":" 作为分隔符
        return tenantPrefix.replace("/", "-") + "__" + threadId;
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
        var resolvedUserId = io.agentmanager.framework.util.PathSafe.sanitize(resolveUserId(userId));

        log.info("[invoke] start: threadId={}, userId={}, messageLen={}", fullThreadId, resolvedUserId, message.length());
        try {
            var ctx = RuntimeContext.builder()
                .sessionId(fullThreadId)
                .userId(resolvedUserId)
                .build();

            var userMsg = new UserMessage("user", message);
            var result = agent.call(List.of(userMsg), ctx).block();

            var responseText = result != null ? result.getTextContent() : "";
            log.info("[invoke] done: threadId={}, responseLen={}", fullThreadId, responseText.length());
            return Map.of("response", responseText, "thread_id", threadId);
        } catch (Exception e) {
            log.error("[invoke] failed: threadId={}, userId={}, error={}", fullThreadId, resolvedUserId, e.getMessage(), e);
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
        var resolvedUserId = io.agentmanager.framework.util.PathSafe.sanitize(resolveUserId(userId));
        var ctx = RuntimeContext.builder()
            .sessionId(fullThreadId)
            .userId(resolvedUserId)
            .build();

        log.info("[invokeStream] start: threadId={}, userId={}, messageLen={}", fullThreadId, resolvedUserId, message.length());

        var userMsg = new UserMessage("user", message);

        return Flux.create(sink -> {
            sink.next(Map.of("type", "task_update", "id", tid, "state", "working"));

            agent.streamEvents(List.of(userMsg), ctx)
                .doOnNext(event -> forwardEvent(sink, event, tid, fullThreadId))
                .doOnError(e -> {
                    log.error("[invokeStream] stream error: threadId={}, error={}", fullThreadId, e.getMessage(), e);
                    sink.next(Map.of("type", "error", "task_id", tid, "error", e.getMessage()));
                    sink.next(Map.of("type", "done"));
                    sink.complete();
                })
                .doOnComplete(() -> {
                    log.info("[invokeStream] done: threadId={}", fullThreadId);
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

    /** 当前 agent（volatile，reload 切换后立即可见）。 */
    public io.agentscope.harness.agent.HarnessAgent getAgent() {
        return this.agent;
    }

    /**
     * OAF reload 原子切换：替换 agent 引用并返回旧实例（调用方负责旧 agent 的 MCP 收尾）。
     * 下一轮对话生效；进行中 turn 已捕获旧引用，继续在旧 agent 上完成。
     */
    public io.agentscope.harness.agent.HarnessAgent swapAgent(io.agentscope.harness.agent.HarnessAgent next) {
        var old = this.agent;
        this.agent = next;
        return old;
    }

    /**
     * 逐事件转发（invokeStream 与 resumeWithConfirmStream 共用）：
     * AgentScope 事件 → 前端 SSE 词表（含 AGENT_END → task_update completed + done）。
     * HITL：RequireUserConfirmEvent → permission_ask，并缓存 ToolUseBlock 供确认端点恢复（6.2.1）。
     */
    private void forwardEvent(FluxSink<Map<String, Object>> sink, AgentEvent event, String tid, String fullThreadId) {
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
            log.info("[DEBUG-RESUME] TOOL_RESULT_TEXT_DELTA tool={} delta={}",
                e.getToolCallName(), String.valueOf(e.getDelta()).length() > 200
                    ? String.valueOf(e.getDelta()).substring(0, 200) : e.getDelta());
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

        // ===== HITL 权限确认事件（6.2.1）=====
        else if (type == AgentEventType.REQUIRE_USER_CONFIRM) {
            var e = (RequireUserConfirmEvent) event;
            var m = new LinkedHashMap<String, Object>();
            m.put("type", "permission_ask");
            m.put("task_id", tid);
            var calls = e.getToolCalls().stream().map(tc -> {
                var c = new LinkedHashMap<String, Object>();
                c.put("tool_call_id", tc.getId());          // ✅ javap 确认：getId() 返回 String
                c.put("name", tc.getName());
                c.put("input", tc.getInput());
                // ToolUseBlock 无 getSuggestedRules()（javap 验证），建议规则在 PermissionDecision 上（P2）
                return c;
            }).toList();
            m.put("tool_calls", calls);
            putIfNotNull(m, "reply_id", e.getReplyId());    // ✅ javap 确认：getReplyId() 返回 String
            // 缓存 ToolUseBlock 供确认端点回填 ConfirmResult（6.3.1；不 complete，agent 暂停等待恢复）
            putConfirmContext(fullThreadId, e);
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
    }

    /**
     * ① 同步版：携带确认结果恢复暂停的 agent（阻塞直至本轮完成）。
     * results: [{tool_call_id, confirmed, accept_rule}]
     * 返回最终回复。
     *
     * 注意：agent.call() 是阻塞 API，**不产出中间事件**，无事件扇出。
     * 长连接场景应使用 ② resumeWithConfirmStream（事件经 SessionEventBus 扇出到原 SSE 连接）。
     */
    public Map<String, Object> resumeWithConfirm(
            String threadId, String userId, List<Map<String, Object>> results) {
        var tid = threadId != null && !threadId.isEmpty() ? threadId : UUID.randomUUID().toString();
        var fullThreadId = makeThreadId(tid);
        log.info("[resume-confirm] start: threadId={}, userId={}, results={}", fullThreadId, userId, results.size());
        var confirmCtx = resolveConfirmContext(fullThreadId);   // 优先 state（无 TTL），回落 confirm_context
        var ctx = buildResumeContext(confirmCtx, fullThreadId, userId);
        var resumeMsg = buildResumeMsg(confirmCtx, results);

        var result = agent.call(List.of(resumeMsg), ctx).block();
        var responseText = result != null ? result.getTextContent() : "";
        log.info("[resume-confirm] done: threadId={}, responseLen={}", fullThreadId, responseText.length());
        return Map.of("response", responseText, "thread_id", tid);
    }

    /**
     * ② 流式版：同上，但以事件流返回（供确认后事件流接口/单次流场景）。
     * 复用 forwardEvent 的事件转发（token/tool_call/tool_result/…/done）。
     *
     * DURABLE_SSE 架构：事件经 SessionEventBus 持久化 + 广播。
     * 保留此方法兼容 A2A 等不走 EventBus 的场景；Channel 流程应使用 ③ resumeWithConfirmEvents。
     */
    public Flux<Map<String, Object>> resumeWithConfirmStream(
            String threadId, String userId, List<Map<String, Object>> results) {
        var tid = threadId != null && !threadId.isEmpty() ? threadId : UUID.randomUUID().toString();
        var fullThreadId = makeThreadId(tid);

        log.info("[resume-confirm-stream] start: threadId={}, userId={}, results={}", fullThreadId, userId, results.size());

        return Flux.create(sink -> {
            try {
                var confirmCtx = resolveConfirmContext(fullThreadId);   // 优先 state（无 TTL），回落 confirm_context
                var ctx = buildResumeContext(confirmCtx, fullThreadId, userId);
                var resumeMsg = buildResumeMsg(confirmCtx, results);
                agent.streamEvents(List.of(resumeMsg), ctx)
                    .doOnNext(event -> forwardEvent(sink, event, tid, fullThreadId))
                    .doOnError(e -> {
                        log.error("[resume-confirm-stream] error: threadId={}, error={}", fullThreadId, e.getMessage(), e);
                        sink.next(Map.of("type", "error", "task_id", tid, "error", e.getMessage()));
                        sink.next(Map.of("type", "done"));
                        sink.complete();
                    })
                    .doOnComplete(() -> {
                        log.info("[resume-confirm-stream] done: threadId={}", fullThreadId);
                        if (!sink.isCancelled()) {
                            sink.complete();
                        }
                    })
                    .subscribe();
            } catch (ConfirmContextNotFoundException | ConfirmAlreadyConsumedException e) {
                log.warn("[resume-confirm-stream] rejected: threadId={}, error={}", fullThreadId, e.getMessage());
                // DB miss / 已消费：以 error 事件帧返回（与 confirm-stream 预检一致）
                sink.next(Map.of("type", "error", "task_id", tid, "error", e.getMessage()));
                sink.next(Map.of("type", "done"));
                sink.complete();
            }
        });
    }

    /**
     * ③ 原始事件流版：返回未经 forwardEvent 序列化的 AgentEvent 流，
     * 供 Controller 层写入 SessionEventBus 实现 DURABLE_SSE（断连不丢失，可重连续传）。
     *
     * <p>调用方负责：
     * <ul>
     *   <li>consumeConfirmContext（CAS 防重复确认）</li>
     *   <li>写入 EventBus + 订阅 EventBus → SSE</li>
     *   <li>租约生命周期管理</li>
     * </ul>
     *
     * @throws ConfirmContextNotFoundException DB 无此 session 的确认上下文
     * @throws ConfirmAlreadyConsumedException 该上下文已被消费
     */
    public Flux<AgentEvent> resumeWithConfirmEvents(
            String threadId, String userId, List<Map<String, Object>> results) {
        var tid = threadId != null && !threadId.isEmpty() ? threadId : UUID.randomUUID().toString();
        var fullThreadId = makeThreadId(tid);

        log.info("[resume-confirm-events] start: threadId={}, userId={}, results={}", fullThreadId, userId, results.size());

        // 先消费确认上下文（CAS 防重复），再构建恢复消息
        var confirmCtx = resolveConfirmContext(fullThreadId);
        var ctx = buildResumeContext(confirmCtx, fullThreadId, userId);
        var resumeMsg = buildResumeMsg(confirmCtx, results);
        return agent.streamEvents(List.of(resumeMsg), ctx);
    }

    /** 构造携带 confirm_results metadata 的恢复消息（调用方已消费确认上下文） */
    private io.agentscope.core.message.Msg buildResumeMsg(ConfirmContext ctx, List<Map<String, Object>> results) {
        var confirmResults = new java.util.ArrayList<io.agentscope.core.event.ConfirmResult>();
        var allConfirmed = true;
        for (var r : results) {
            var toolCallId = (String) r.get("tool_call_id");
            var toolCall = ctx.toolCalls().get(toolCallId);   // 从缓存取原始 ToolUseBlock 实例
            if (toolCall == null) {
                throw new IllegalArgumentException("Unknown tool_call_id: " + toolCallId);
            }
            var confirmedObj = r.get("confirmed");
            var confirmed = Boolean.TRUE.equals(confirmedObj);
            if (!confirmed) allConfirmed = false;
            var acceptRule = Boolean.TRUE.equals(r.getOrDefault("accept_rule", false));
            // ConfirmResult(boolean, ToolUseBlock) — ✅ javap 确认；accept_rule 时用 3-arg 版本
            //（建议规则当前 P2：suggestedRules 在 PermissionDecision 上，先传空规则列表）
            confirmResults.add(acceptRule
                ? new io.agentscope.core.event.ConfirmResult(confirmed, toolCall, List.of())
                : new io.agentscope.core.event.ConfirmResult(confirmed, toolCall));
        }
        var meta = new java.util.HashMap<String, Object>();
        meta.put(io.agentscope.core.message.Msg.METADATA_CONFIRM_RESULTS, confirmResults);  // ✅ javap 确认常量
        // 恢复消息文本需给出强终止信号：该工具调用已被人工批准并将立即执行，
        // 模型不得在工具真正返回后又重复调用（否则 HITL 每次批准都触发重试循环）。
        var text = allConfirmed
            ? "人工已批准上述工具调用，工具将立即执行。如果工具执行成功，请直接向用户汇报结果并结束流程，不得再次调用同一工具。"
            : "人工拒绝了上述工具调用，请不要执行，直接向用户说明。";
        return io.agentscope.core.message.Msg.builder()
            .name("user").role(io.agentscope.core.message.MsgRole.USER)
            .textContent(text)
            .metadata(meta)
            .build();
    }

    /**
     * 构建恢复执行的 RuntimeContext。
     *
     * Channel 流程（ChatStreamController）的会话经 ChatUiChannel 网关路由，
     * 网关按 peer 派生真实会话 key：userId=peer（如 debug-user_xxx），
     * sessionId=网关恒定 gw-hash（storeConfirmContext 按 canonicalKey 确定性推导，
     * 恒为 gw-3f20f08c5499，不能依赖 AgentStartEvent.getSessionId()——该字段为 null）。
     * 恢复必须复用同一 (userId, sessionId) 才能命中网关会话中的 pending 工具调用，
     * 否则 SDK 在 (makeThreadId, vendorKey) 下加载不到上下文 → 全新推理丢失状态。
     *
     * A2A/invoke 流程（forwardEvent 直接 putConfirmContext，无网关路由）无此信息，
     * 回落 makeThreadId + 显式 userId（与触发侧一致）。
     */
    private RuntimeContext buildResumeContext(ConfirmContext confirmCtx, String fullThreadId, String userId) {
        if (confirmCtx.runtimeSessionId() != null && confirmCtx.runtimeUserId() != null) {
            return RuntimeContext.builder()
                .sessionId(confirmCtx.runtimeSessionId())
                .userId(io.agentmanager.framework.util.PathSafe.sanitize(confirmCtx.runtimeUserId()))
                .build();
        }
        return RuntimeContext.builder().sessionId(fullThreadId)
            .userId(io.agentmanager.framework.util.PathSafe.sanitize(resolveUserId(userId))).build();
    }

    // ===== 确认上下文（6.3.1；落库 ConfirmContextStore，跨副本可见）=====

    /** 确认上下文：一个 session 可能同时有多个待确认工具（12.1 批量 ASK）
     *  runtimeSessionId/runtimeUserId：Channel 流程经网关路由后的真实会话 key
     *  （sessionId=gw-hash、userId=peer）；A2A/普通流程为 null，回落 makeThreadId+vendorKey。 */
    record ConfirmContext(
        Map<String, ToolUseBlock> toolCalls,   // tool_call_id → ToolUseBlock
        String replyId,
        Instant createdAt,
        AtomicBoolean consumed,                 // CAS 防重复确认（DB 版：到达时已消费，恒为 true）
        String runtimeSessionId,                // Channel 网关真实 sessionId（gw-hash），非 Channel 为 null
        String runtimeUserId                    // Channel 网关 peer（userId），非 Channel 为 null
    ) {}

    /** 序列化 tool_calls → [{id, name, input}] JSON（confirm_context 表存储形态，SPIKE S1） */
    private static List<Map<String, Object>> toolCallsJson(List<ToolUseBlock> calls) {
        return calls.stream().map(tc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", tc.getId());
            m.put("name", tc.getName());
            m.put("input", tc.getInput());
            return m;
        }).toList();
    }

    /**
     * Channel 流程存储确认上下文（ChatStreamController 调用；rawSessionId 经 makeThreadId 补全前缀）。
     * Channel 会话经 ChatUiChannel 网关路由，真实会话 key 为 (userId=peer, sessionId=gw-hash)：
     *  - sessionId 由网关按 canonicalKey 确定性推导（恒为 gw-3f20f08c5499，同进程所有 peer 共享）
     *  - userId 即 peer（= rawSessionId，如 debug-user_mt1xxx）
     * HITL 恢复必须复用该组合才能命中 pending 工具调用（见 buildResumeContext）。
     */
    public void storeConfirmContext(String rawSessionId, io.agentscope.core.event.AgentEvent event) {
        if (event instanceof RequireUserConfirmEvent e) {
            var fullThreadId = makeThreadId(rawSessionId);
            var gwSessionId = channelGatewaySessionId();
            log.info("[HITL] storeConfirmContext: rawSessionId={}, fullThreadId={}, gatewaySessionId={}, replyId={}, tools={}",
                rawSessionId, fullThreadId, gwSessionId, e.getReplyId(),
                e.getToolCalls().stream().map(tc -> tc.getName()).toList());
            putConfirmContext(fullThreadId, e, gwSessionId, rawSessionId);
        }
    }

    /**
     * 复刻 HarnessGateway 的网关会话 id 派生：
     * sessionId = "gw-" + SHA-256(canonicalKey) 前 6 字节 hex 形式（12 字符）。
     * 框架 Channel 通道走 ChatUiChannel 默认配置（DmScope.MAIN、globalDefaultAgentId=main），
     * MsgContext.canonicalKey() = "chatui" + "|x:agentId=main"（extra 按 key 排序）。
     * 同进程所有 peer 共享同一会话 id，peer 仅体现在 userId——与 DB 实测一致。
     */
    private String channelGatewaySessionId() {
        var canonicalKey = "chatui" + "|x:agentId=main";
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalKey.getBytes(StandardCharsets.UTF_8));
            return "gw-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** 缓存待确认上下文（forwardEvent 收到 RequireUserConfirmEvent 时调用；A2A/普通流程，无网关路由信息） */
    void putConfirmContext(String sessionId, RequireUserConfirmEvent event) {
        putConfirmContext(sessionId, event, null, null);
    }

    /** 落库待确认上下文（可携带 Channel 网关真实会话 key；同 session 新 ASK 覆盖旧条目） */
    void putConfirmContext(String sessionId, RequireUserConfirmEvent event,
                           String runtimeSessionId, String runtimeUserId) {
        confirmContextStore.put(sessionId,
            toolCallsJson(event.getToolCalls()), event.getReplyId(), runtimeSessionId, runtimeUserId);
    }

    /**
     * 预检确认可用性（confirm-stream 端点先查后流）。
     *
     * <p>两条来源任一可用即通过：
     * <ol>
     *   <li><b>AgentState</b>（首选）：存在 ASKING 工具即表示该轮仍挂起，与会话同寿命，
     *       不受 confirm_context 的 30 分钟 TTL 限制——修复了"确认卡放置超时后必然 404"的问题</li>
     *   <li><b>confirm_context</b>（兜底）：老会话或 state 尚未写入时的既有路径</li>
     * </ol>
     * 两者皆无 → 404 {@code confirm_context_not_found}（语义保持：确实没有可确认的挂起工具）。
     */
    public void checkConfirmAvailable(String sessionId) {
        var fullThreadId = makeThreadId(sessionId);   // 补全 tenant 前缀，与 putConfirmContext 存储 key 一致
        log.debug("[HITL] checkConfirmAvailable: sessionId={}, fullThreadId={}", sessionId, fullThreadId);
        if (agentStateReader != null && loadConfirmContextFromState(fullThreadId) != null) {
            log.debug("[HITL] checkConfirmAvailable: satisfied by agent_state for {}", fullThreadId);
            return;
        }
        confirmContextStore.checkAvailable(fullThreadId);
    }

    /** DB CAS 取出并标记已消费（防重复确认 → 409） */
    ConfirmContext consumeConfirmContext(String sessionId) {
        log.debug("[HITL] consumeConfirmContext: sessionId={}", sessionId);
        var row = confirmContextStore.consume(sessionId);
        var toolCalls = new LinkedHashMap<String, ToolUseBlock>();
        for (var tc : row.toolCalls()) {
            toolCalls.put(tc.getId(), tc);
        }
        return new ConfirmContext(toolCalls, row.replyId(), Instant.now(),
            new AtomicBoolean(true), row.runtimeSessionId(), row.runtimeUserId());
    }

    /**
     * 从 AgentState 重建确认上下文（HITL 恢复的首选路径）。
     *
     * <p>官方 SDK 2.0.3 把 ASKING 的 ToolUseBlock 与其 replyId 持久化在 state 里，
     * 因此即使 confirm_context 行已被 TTL 清理，仍可恢复执行——这是"确认卡长期可操作"的基础。
     *
     * <p>实现要点（对齐 docs/history-agentstate-design.md §8-1/8-2 的修复）：
     * <ul>
     *   <li><b>state 只读一份</b>：agent_state 的 session_id 是 {@code peer:canonicalKey} 形态，
     *       fullThreadId（{@code tenant__peer}）恒不命中——按候选顺序 (fullThreadId, peer)
     *       一次读取，asking/identity/replyId 全部从同一份快照派生，不再逐项重查</li>
     *   <li><b>replyId 同源取出</b>：此前按 fullThreadId 重查恒为空串，SDK 只能兜底生成，
     *       与升级 2.0.3 的目标相悖</li>
     * </ul>
     *
     * @return 重建的确认上下文；state 中无 ASKING 工具时返回 null（调用方回落 confirm_context）
     */
    private ConfirmContext loadConfirmContextFromState(String fullThreadId) {
        if (agentStateReader == null) {
            return null;
        }
        var snapshot = agentStateReader.loadAskingSnapshot(fullThreadId, stripTenantPrefix(fullThreadId));
        if (snapshot.isEmpty()) {
            return null;
        }
        log.info("[HITL] loadConfirmContextFromState: threadId={}, tools={}, replyId={}, gwSession={}, peer={}",
            fullThreadId, snapshot.toolUseBlocks().keySet(), snapshot.replyId(),
            snapshot.identity().get("session_id"), snapshot.identity().get("user_id"));
        return new ConfirmContext(
            snapshot.toolUseBlocks(),
            snapshot.replyId(),
            Instant.now(),
            new AtomicBoolean(true),
            snapshot.identity().get("session_id"),
            snapshot.identity().get("user_id"));
    }

    /** 去掉 tenant 前缀（{@code slug__threadId} → {@code threadId}） */
    private String stripTenantPrefix(String fullThreadId) {
        var prefix = tenantPrefix.replace("/", "-") + "__";
        return fullThreadId.startsWith(prefix) ? fullThreadId.substring(prefix.length()) : fullThreadId;
    }

    /**
     * 取得恢复所需的确认上下文。**参数完整性优先**：
     *
     * <ol>
     *   <li>先读 confirm_context 表（非破坏性 {@code findPending}）：该表的 toolCalls 来自
     *       {@code RequireUserConfirmEvent}，**携带完整工具参数**；</li>
     *   <li>表无行（已 TTL 清理 / 老会话）时回落 AgentState 重建。</li>
     * </ol>
     *
     * <p><b>为什么不能反过来</b>（2026-09-19 修复，e2e-ci-plan §11.3 D1）：SDK 持久化到
     * agent_state 的 assistant {@code tool_use.input} 在 ASKING 态**恒为空对象**，
     * 而 state 路径此前被优先使用——用它重建出的 ToolUseBlock 参数为空，导致恢复执行时
     * 工具收到空参数（如 {@code argument "content" is null}）而失败。实测：挂起时
     * permission_ask 与 confirm_context 的参数都完好，唯独 state 里是 {@code {}}。
     *
     * <p>两路都命中时：表内参数为权威；仅当表中某 toolCallId 参数为空而 state 有值
     * （反向异常）才以 state 补齐。取用后把表行标记已消费，保证二次提交得到 409。
     *
     * @throws ConfirmContextNotFoundException 两处都找不到挂起的 ASKING 工具
     */
    private ConfirmContext resolveConfirmContext(String fullThreadId) {
        var fromState = loadConfirmContextFromState(fullThreadId);
        var fromTable = confirmContextStore.findPending(fullThreadId).orElse(null);

        if (fromTable != null) {
            var tools = new LinkedHashMap<String, io.agentscope.core.message.ToolUseBlock>();
            for (var tc : fromTable.toolCalls()) {
                tools.put(tc.getId(), enrichWithStateInput(tc, fromState));
            }
            // state 有额外挂起工具（表行覆盖不全）时并入，避免漏确认项
            if (fromState != null) {
                fromState.toolCalls().forEach(tools::putIfAbsent);
            }
            try {
                confirmContextStore.consume(fullThreadId);   // 二次提交 → 409 already processed
            } catch (Exception e) {
                log.debug("[HITL] confirm_context consume skipped for {}: {}", fullThreadId, e.getMessage());
            }
            String replyId = fromTable.replyId() != null && !fromTable.replyId().isBlank()
                ? fromTable.replyId()
                : (fromState != null ? fromState.replyId() : "");
            log.info("[HITL] resolveConfirmContext: table hit (complete params) for {}, tools={}, replyId={}",
                fullThreadId, tools.keySet(), replyId);
            return new ConfirmContext(tools, replyId, Instant.now(), new AtomicBoolean(true),
                fromState != null ? fromState.runtimeSessionId() : null,
                fromState != null ? fromState.runtimeUserId() : null);
        }

        if (fromState != null) {
            log.info("[HITL] resolveConfirmContext: state fallback for {} (table empty — params may be incomplete)",
                fullThreadId);
            return fromState;
        }
        log.debug("[HITL] no ASKING tools in state for {}, falling back to confirm_context", fullThreadId);
        return consumeConfirmContext(fullThreadId);
    }

    /** 表内参数为空时用 state 的同 id 参数补齐（防御性：state 参数通常为空，仅在反向异常时生效） */
    private io.agentscope.core.message.ToolUseBlock enrichWithStateInput(
            io.agentscope.core.message.ToolUseBlock fromTable, ConfirmContext fromState) {
        if (fromState == null || fromTable.getInput() == null || !fromTable.getInput().isEmpty()) {
            return fromTable;
        }
        var stateTool = fromState.toolCalls().get(fromTable.getId());
        if (stateTool == null || stateTool.getInput() == null || stateTool.getInput().isEmpty()) {
            return fromTable;
        }
        log.info("[HITL] enriching tool {} input from agent_state ({} keys)",
            fromTable.getId(), stateTool.getInput().size());
        // content 同步取 state 侧完整参数 JSON：content 只用于 ToolValidator 校验，
        // 若保留表侧空参数的 "{}" 会误报 'Parameter validation failed ... missing'
        return new io.agentscope.core.message.ToolUseBlock(
            fromTable.getId(), fromTable.getName(), stateTool.getInput(),
            stateTool.getContent() != null ? stateTool.getContent() : fromTable.getContent(),
            fromTable.getMetadata(), fromTable.getState());
    }

    /** 清理确认上下文（供测试/运维使用；恢复完成后不主动清理——保留 consumed 条目以正确返回 409，且同 session 新 ASK 会覆盖） */
    void removeConfirmContext(String sessionId) {
        confirmContextStore.delete(makeThreadId(sessionId));
    }

    /** 查询待确认上下文（status 端点用，返回前端 pendingConfirm 词表或 null） */
    public Map<String, Object> findPendingConfirm(String sessionId) {
        var fullThreadId = makeThreadId(sessionId);
        return confirmContextStore.findPending(fullThreadId)
            .map(p -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("reply_id", p.replyId());
                m.put("tools", p.toolsJson());
                m.put("created_at", p.createdAt() != null ? p.createdAt().toString() : "");
                return Map.<String, Object>copyOf(m);
            })
            .orElse(null);
    }

    /** 确认上下文不存在或已过期（404 → confirm_context_not_found，12.5） */
    public static class ConfirmContextNotFoundException extends RuntimeException {
        public ConfirmContextNotFoundException(String sessionId) {
            super("confirm_context_not_found: session '" + sessionId
                + "' not found or confirm context expired");
        }
    }

    /** 确认已被处理（409 → confirm_already_consumed，DB CAS 防重复，12.5） */
    public static class ConfirmAlreadyConsumedException extends RuntimeException {
        public ConfirmAlreadyConsumedException(String sessionId) {
            super("confirm_already_consumed: session '" + sessionId + "' already processed");
        }
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
