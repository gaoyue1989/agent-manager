package io.agentmanager.framework.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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

    /** 确认上下文缓存 TTL（12.2 设计：超时未确认自动失效） */
    private static final Duration CONFIRM_TTL = Duration.ofMinutes(30);
    /** 过期确认上下文清理周期 */
    private static final long CONFIRM_SWEEP_INTERVAL_MIN = 5;

    private final OafConfig oafConfig;
    private final String tenantPrefix;
    private final LLMLogger llmLogger;
    private final SessionEventBus eventBus;

    /** 确认上下文缓存：sessionId(fullThreadId) → 待确认工具（6.3.1；ConfirmResult 需原始 ToolUseBlock 实例） */
    private final ConcurrentHashMap<String, ConfirmContext> confirmCache = new ConcurrentHashMap<>();
    /** 过期条目定时清理（30min TTL） */
    private final ScheduledExecutorService confirmSweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "confirm-sweeper");
        t.setDaemon(true);
        return t;
    });

    private io.agentscope.harness.agent.HarnessAgent agent;
    private final List<Map<String, Object>> mcpConfigs;

    public AgentRuntimeService(
        OafConfig oafConfig,
        io.agentscope.harness.agent.HarnessAgent agent,
        List<Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger,
        SessionEventBus eventBus
    ) {
        this.oafConfig = oafConfig;
        this.tenantPrefix = oafConfig.slug();
        this.agent = agent;
        this.mcpConfigs = mcpConfigs;
        this.llmLogger = llmLogger;
        this.eventBus = eventBus;
        confirmSweeper.scheduleWithFixedDelay(this::sweepExpiredConfirms,
            CONFIRM_SWEEP_INTERVAL_MIN, CONFIRM_SWEEP_INTERVAL_MIN, TimeUnit.MINUTES);
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
        var ctx = RuntimeContext.builder().sessionId(fullThreadId)
            .userId(resolveUserId(userId)).build();

        var resumeMsg = buildResumeMsg(fullThreadId, results);   // CAS 消费，防重复确认

        var result = agent.call(List.of(resumeMsg), ctx).block();
        var responseText = result != null ? result.getTextContent() : "";
        return Map.of("response", responseText, "thread_id", tid);
    }

    /**
     * ② 流式版：同上，但以事件流返回（供确认后事件流接口/长连接场景）。
     * 复用 forwardEvent 的事件转发（token/tool_call/tool_result/…/done）。
     *
     * 长连接场景：confirm-stream 的事件同时通过 eventBus 扇出到 SessionEventBus，
     * 原长连接 SSE 订阅方在同一连接上实时收到恢复事件（无需重连）。
     */
    public Flux<Map<String, Object>> resumeWithConfirmStream(
            String threadId, String userId, List<Map<String, Object>> results) {
        var tid = threadId != null && !threadId.isEmpty() ? threadId : UUID.randomUUID().toString();
        var fullThreadId = makeThreadId(tid);
        var ctx = RuntimeContext.builder().sessionId(fullThreadId)
            .userId(resolveUserId(userId)).build();

        return Flux.create(sink -> {
            try {
                var resumeMsg = buildResumeMsg(fullThreadId, results);   // CAS 消费，防重复确认
                agent.streamEvents(List.of(resumeMsg), ctx)
                    .doOnNext(event -> {
                        forwardEvent(sink, event, tid, fullThreadId);    // 含 AGENT_END→done
                        eventBus.emit(tid, event);                       // 扇出到 SessionEventBus（用 raw sessionId 匹配前端 SSE 订阅）
                    })
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
                // 缓存 miss / 已消费：以 error 事件帧返回（与 confirm-stream 预检一致）
                sink.next(Map.of("type", "error", "task_id", tid, "error", e.getMessage()));
                sink.next(Map.of("type", "done"));
                sink.complete();
            }
        });
    }

    /** 构造携带 confirm_results metadata 的恢复消息（公共方法，缓存 CAS 消费） */
    private io.agentscope.core.message.Msg buildResumeMsg(String fullThreadId, List<Map<String, Object>> results) {
        var ctx = consumeConfirmContext(fullThreadId);   // CAS 取出并标记已消费（6.3.1）
        var confirmResults = new java.util.ArrayList<io.agentscope.core.event.ConfirmResult>();
        for (var r : results) {
            var toolCallId = (String) r.get("tool_call_id");
            var toolCall = ctx.toolCalls().get(toolCallId);   // 从缓存取原始 ToolUseBlock 实例
            if (toolCall == null) {
                throw new IllegalArgumentException("Unknown tool_call_id: " + toolCallId);
            }
            var confirmedObj = r.get("confirmed");
            var confirmed = !(confirmedObj instanceof Boolean b) || b;   // 缺省视为批准
            var acceptRule = Boolean.TRUE.equals(r.getOrDefault("accept_rule", false));
            // ConfirmResult(boolean, ToolUseBlock) — ✅ javap 确认；accept_rule 时用 3-arg 版本
            //（建议规则当前 P2：suggestedRules 在 PermissionDecision 上，先传空规则列表）
            confirmResults.add(acceptRule
                ? new io.agentscope.core.event.ConfirmResult(confirmed, toolCall, List.of())
                : new io.agentscope.core.event.ConfirmResult(confirmed, toolCall));
        }
        var meta = new java.util.HashMap<String, Object>();
        meta.put(io.agentscope.core.message.Msg.METADATA_CONFIRM_RESULTS, confirmResults);  // ✅ javap 确认常量
        return io.agentscope.core.message.Msg.builder()
            .name("user").role(io.agentscope.core.message.MsgRole.USER)
            .textContent("user confirmed")
            .metadata(meta)
            .build();
    }

    // ===== 确认上下文缓存（6.3.1）=====

    /** 确认上下文：一个 session 可能同时有多个待确认工具（12.1 批量 ASK） */
    record ConfirmContext(
        Map<String, ToolUseBlock> toolCalls,   // tool_call_id → ToolUseBlock
        String replyId,
        Instant createdAt,
        AtomicBoolean consumed                 // CAS 防重复确认
    ) {}

    /**
     * Channel 流程存储确认上下文（SessionStreamController 调用；rawSessionId 经 makeThreadId 补全前缀）。
     * 同 session 新 ASK 覆盖旧条目。
     */
    public void storeConfirmContext(String rawSessionId, io.agentscope.core.event.AgentEvent event) {
        if (event instanceof RequireUserConfirmEvent e) {
            var fullThreadId = makeThreadId(rawSessionId);
            log.info("[HITL] storeConfirmContext: rawSessionId={}, fullThreadId={}, replyId={}, tools={}",
                rawSessionId, fullThreadId, e.getReplyId(),
                e.getToolCalls().stream().map(tc -> tc.getName()).toList());
            putConfirmContext(fullThreadId, e);
        }
    }

    /** 缓存待确认上下文（forwardEvent 收到 RequireUserConfirmEvent 时调用；同 session 新 ASK 覆盖旧条目） */
    void putConfirmContext(String sessionId, RequireUserConfirmEvent event) {
        var toolCalls = event.getToolCalls().stream()
            .collect(Collectors.toMap(tc -> tc.getId(), tc -> tc));
        confirmCache.put(sessionId,
            new ConfirmContext(toolCalls, event.getReplyId(), Instant.now(), new AtomicBoolean(false)));
    }

    /** 预检确认可用性（confirm-stream 端点先查后流）：缓存存在、未过期、未消费 */
    public void checkConfirmAvailable(String sessionId) {
        var fullThreadId = makeThreadId(sessionId);   // 补全 tenant 前缀，与 putConfirmContext 存储 key 一致
        var ctx = confirmCache.get(fullThreadId);
        if (ctx == null || ctx.createdAt().plus(CONFIRM_TTL).isBefore(Instant.now())) {
            throw new ConfirmContextNotFoundException(sessionId);
        }
        if (ctx.consumed().get()) {
            throw new ConfirmAlreadyConsumedException(sessionId);
        }
    }

    /** CAS 取出并标记已消费（防重复确认 → 409） */
    ConfirmContext consumeConfirmContext(String sessionId) {
        var ctx = confirmCache.get(sessionId);
        if (ctx == null || ctx.createdAt().plus(CONFIRM_TTL).isBefore(Instant.now())) {
            throw new ConfirmContextNotFoundException(sessionId);
        }
        if (!ctx.consumed().compareAndSet(false, true)) {
            throw new ConfirmAlreadyConsumedException(sessionId);
        }
        return ctx;
    }

    /** 清理确认上下文（供测试/运维使用；恢复完成后不主动清理——保留 consumed 条目以正确返回 409，且同 session 新 ASK 会覆盖） */
    void removeConfirmContext(String sessionId) {
        confirmCache.remove(sessionId);
    }

    /** 定时清理过期确认上下文（30min TTL） */
    private void sweepExpiredConfirms() {
        var now = Instant.now();
        confirmCache.entrySet().removeIf(e -> e.getValue().createdAt().plus(CONFIRM_TTL).isBefore(now));
    }

    /** 确认上下文不存在或已过期（404 → confirm_context_not_found，12.5） */
    public static class ConfirmContextNotFoundException extends RuntimeException {
        public ConfirmContextNotFoundException(String sessionId) {
            super("confirm_context_not_found: session '" + sessionId
                + "' not found or confirm context expired");
        }
    }

    /** 确认已被处理（409 → confirm_already_consumed，CAS 防重复，12.5） */
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
