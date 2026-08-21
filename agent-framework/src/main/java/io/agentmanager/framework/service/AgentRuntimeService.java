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

    private io.agentscope.harness.agent.HarnessAgent agent;
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
                .doOnNext(event -> forwardEvent(sink, event, tid, fullThreadId))
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
        var confirmCtx = consumeConfirmContext(fullThreadId);   // CAS 消费，防重复确认
        var ctx = buildResumeContext(confirmCtx, fullThreadId, userId);
        var resumeMsg = buildResumeMsg(confirmCtx, results);

        var result = agent.call(List.of(resumeMsg), ctx).block();
        var responseText = result != null ? result.getTextContent() : "";
        return Map.of("response", responseText, "thread_id", tid);
    }

    /**
     * ② 流式版：同上，但以事件流返回（供确认后事件流接口/单次流场景）。
     * 复用 forwardEvent 的事件转发（token/tool_call/tool_result/…/done）。
     *
     * 无状态架构：不再经 SessionEventBus 扇出（长连接已移除），事件仅经 SSE 直吐。
     */
    public Flux<Map<String, Object>> resumeWithConfirmStream(
            String threadId, String userId, List<Map<String, Object>> results) {
        var tid = threadId != null && !threadId.isEmpty() ? threadId : UUID.randomUUID().toString();
        var fullThreadId = makeThreadId(tid);

        return Flux.create(sink -> {
            try {
                var confirmCtx = consumeConfirmContext(fullThreadId);   // DB CAS 消费，防重复确认
                var ctx = buildResumeContext(confirmCtx, fullThreadId, userId);
                var resumeMsg = buildResumeMsg(confirmCtx, results);
                agent.streamEvents(List.of(resumeMsg), ctx)
                    .doOnNext(event -> forwardEvent(sink, event, tid, fullThreadId))
                    .doOnError(e -> {
                        log.error("resume stream error: {}", e.getMessage(), e);
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
            } catch (ConfirmContextNotFoundException | ConfirmAlreadyConsumedException e) {
                // DB miss / 已消费：以 error 事件帧返回（与 confirm-stream 预检一致）
                sink.next(Map.of("type", "error", "task_id", tid, "error", e.getMessage()));
                sink.next(Map.of("type", "done"));
                sink.complete();
            }
        });
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
            var confirmed = !(confirmedObj instanceof Boolean b) || b;   // 缺省视为批准
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
     * Channel 流程（SessionStreamController）的会话经 ChatUiChannel 网关路由，
     * 网关按 peer 派生真实会话 key：userId=peer（如 debug-user:xxx），
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
                .userId(confirmCtx.runtimeUserId())
                .build();
        }
        return RuntimeContext.builder().sessionId(fullThreadId)
            .userId(resolveUserId(userId)).build();
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
     * Channel 流程存储确认上下文（SessionStreamController 调用；rawSessionId 经 makeThreadId 补全前缀）。
     * Channel 会话经 ChatUiChannel 网关路由，真实会话 key 为 (userId=peer, sessionId=gw-hash)：
     *  - sessionId 由网关按 canonicalKey 确定性推导（恒为 gw-3f20f08c5499，同进程所有 peer 共享）
     *  - userId 即 peer（= rawSessionId，如 debug-user:mt1xxx）
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

    /** 预检确认可用性（confirm-stream 端点先查后流）：DB 行存在、未过期、未消费 */
    public void checkConfirmAvailable(String sessionId) {
        var fullThreadId = makeThreadId(sessionId);   // 补全 tenant 前缀，与 putConfirmContext 存储 key 一致
        confirmContextStore.checkAvailable(fullThreadId);
    }

    /** DB CAS 取出并标记已消费（防重复确认 → 409） */
    ConfirmContext consumeConfirmContext(String sessionId) {
        var row = confirmContextStore.consume(sessionId);
        var toolCalls = new LinkedHashMap<String, ToolUseBlock>();
        for (var tc : row.toolCalls()) {
            toolCalls.put(tc.getId(), tc);
        }
        return new ConfirmContext(toolCalls, row.replyId(), Instant.now(),
            new AtomicBoolean(true), row.runtimeSessionId(), row.runtimeUserId());
    }

    /** 清理确认上下文（供测试/运维使用；恢复完成后不主动清理——保留 consumed 条目以正确返回 409，且同 session 新 ASK 会覆盖） */
    void removeConfirmContext(String sessionId) {
        confirmContextStore.delete(makeThreadId(sessionId));
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
