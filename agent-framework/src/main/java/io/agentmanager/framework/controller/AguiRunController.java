package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionEventTailer;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.SkillInjectionService;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseGuard;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentmanager.framework.service.TurnToolSummaryTracker;
import io.agentmanager.framework.service.UiContextStore;
import io.agentmanager.framework.util.PathSafe;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * AG-UI 协议输出面（agui-native-parity-design §5）。
 *
 * <ul>
 *   <li>{@code POST /agui/runs} —— 标准 AG-UI run：RunAgentInput 投影（threadId + 最后一条
 *       user 消息；resume[] 走 HITL 恢复）→ SSE AguiEvent 流；</li>
 *   <li>{@code GET /agui/threads/{threadId}/events?afterSeq=&runId=} —— 平台扩展续传端点：
 *       复用 SessionEventTailer 回放/追赶/终态探测骨架 + {@link AguiTailRenderer} 渲染。</li>
 * </ul>
 *
 * <p><b>生产对等语义</b>（与 native 面 /threads/chat、/threads/{sid}/confirm-stream 逐项对齐）：
 * 会话经同一 ChatUiChannel 网关路径（运行时会话身份/ASKING 快照/恢复上下文完全同源）；
 * turn 仲裁同一 turn_lease；HITL 同源 confirm_context（双面互认）；事件同流双编码
 * （p=native 词表、pa=AG-UI 帧数组）——native 面的 /subscribe、/status、history 对 AG-UI
 * 轮全程可见。SSE 断连不杀 run（事件照常落库，客户端经续传端点补齐）。
 *
 * <p>身份只认请求头（X-User-Id，网关注入）；forwardedProps 不消费（官方同款约束：
 * 不可作可信身份来源）。工具链由服务端 Toolkit 承担，请求体 tools 列表暂不消费。
 */
@RestController
@RequestMapping("/agui")
@ConditionalOnProperty(name = "agent.agui.enabled", havingValue = "true")
public class AguiRunController {

    private static final Logger log = LoggerFactory.getLogger(AguiRunController.class);

    static Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(120);
    /** 阻塞式租约等待窗（resume 路径用，与 ConfirmController 同口径） */
    static Duration ACQUIRE_WAIT = Duration.ofSeconds(15);

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();
    private static final AguiEventEncoder ENCODER = new AguiEventEncoder();

    private final AgentRuntimeService runtimeService;
    private final TurnLeaseStore turnLeaseStore;
    private final SessionEventBus eventBus;
    private final SessionEventTailer tailer;
    private final SessionUserStore sessionUserStore;
    private final SkillInjectionService skillInjectionService;
    private final ToolAuditStore toolAuditStore;
    private final McpToolRegistrar mcpToolRegistrar;
    private final AguiEventCodec codec;
    private final Duration heartbeatInterval;

    /** toolCallId → 真实工具名（turn 内有效；ToolCallStartEvent 是唯一带真实名的事件） */
    private final java.util.concurrent.ConcurrentHashMap<String, String> toolCallNames =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** present_file/present_url 结果文本累积（toolCallId → 文本桶，64KB 上限） */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> presentFileBuffers =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final int PRESENT_FILE_BUFFER_MAX = 64 * 1024;

    public AguiRunController(AgentRuntimeService runtimeService,
                             TurnLeaseStore turnLeaseStore,
                             SessionEventBus eventBus,
                             SessionEventTailer tailer,
                             SessionUserStore sessionUserStore,
                             SkillInjectionService skillInjectionService,
                             ToolAuditStore toolAuditStore,
                             McpToolRegistrar mcpToolRegistrar,
                             AguiEventCodec codec,
                             AgentManagerProperties managerProps) {
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
        this.eventBus = eventBus;
        this.tailer = tailer;
        this.sessionUserStore = sessionUserStore;
        this.skillInjectionService = skillInjectionService;
        this.toolAuditStore = toolAuditStore;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.codec = codec;
        this.heartbeatInterval = Duration.ofSeconds(managerProps.sse().heartbeatSeconds());
    }

    // ===== 请求模型（RunAgentInput 的平台侧投影：只取本面消费的字段） =====

    /** AG-UI run 请求体（官方 RunAgentInput 子集；tools/state/context 暂不消费） */
    public record AguiRunRequest(String threadId, String runId, List<AguiMessageDTO> messages,
                                 List<AguiResumeDTO> resume) {
    }

    /** AG-UI message（content 为 string 或 [{type:"text",text:…}] 数组，取 text 聚合） */
    public record AguiMessageDTO(String id, String role, Object content) {
    }

    /** AG-UI resume 条目：interruptId（runId:toolCallId）、status=resolved|cancelled、payload */
    public record AguiResumeDTO(String interruptId, String status, Map<String, Object> payload) {
    }

    // ===== POST /agui/runs =====

    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> runs(@RequestBody AguiRunRequest body,
                                              @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        var threadId = PathSafe.sanitize(body.threadId() == null || body.threadId().isBlank()
            ? UUID.randomUUID().toString() : body.threadId());
        var runId = PathSafe.sanitize(body.runId() == null || body.runId().isBlank()
            ? UUID.randomUUID().toString() : body.runId());
        var userId = PathSafe.sanitize(headerUserId == null || headerUserId.isBlank()
            ? "debug-user" : headerUserId);

        log.info("[agui] run start: threadId={}, runId={}, userId={}, hasResume={}",
            threadId, runId, userId, body.resume() != null && !body.resume().isEmpty());

        // 归属校验（与 /chat、/subscribe 同口径）：已存在会话只允许归属用户操作
        if (!io.agentmanager.framework.service.SessionAccessGuard.ownedBy(
                sessionUserStore, threadId, userId)) {
            log.info("[agui] reject foreign session: threadId={}, userId={}", threadId, userId);
            return Flux.just(runErrorSSE(threadId, runId,
                "thread_not_found: session '" + threadId + "' not found"));
        }

        // 恢复链路：resume[] 非空 → HITL 恢复 turn（镜像 ConfirmController.confirm-stream）
        if (body.resume() != null && !body.resume().isEmpty()) {
            return resumeRun(threadId, runId, body.resume());
        }

        // 新对话轮：提取最后一条 user 消息（官方 AguiRequestProcessor.extractLatestUserMessage
        // 同语义——平台运行时自带服务端历史，AG-UI 客户端全量回传的 messages 只取增量）
        String text = extractLatestUserText(body.messages());
        if (text == null || text.isBlank()) {
            return Flux.just(runErrorSSE(threadId, runId, "invalid_input_error: no user message"));
        }
        return newRun(threadId, runId, userId, text);
    }

    /** 最后一条 user 消息的文本（content 为 string 或 [{type:"text",text:…}]） */
    static String extractLatestUserText(List<AguiMessageDTO> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            var m = messages.get(i);
            if (m == null || !"user".equalsIgnoreCase(m.role())) {
                continue;
            }
            var c = m.content();
            if (c instanceof String s) {
                return s;
            }
            if (c instanceof List<?> blocks) {
                var sb = new StringBuilder();
                for (var b : blocks) {
                    if (b instanceof Map<?, ?> bm && "text".equals(String.valueOf(bm.get("type")))) {
                        sb.append(String.valueOf(bm.get("text")));
                    }
                }
                return sb.toString();
            }
            return c == null ? null : String.valueOf(c);
        }
        return null;
    }

    // ===== 新对话轮（镜像 ChatStreamController.chat） =====

    private Flux<ServerSentEvent<String>> newRun(String threadId, String runId, String userId,
                                                 String text) {
        sessionUserStore.upsert(threadId, userId);
        return Flux.<ServerSentEvent<String>>create(sink -> {
            var token = turnLeaseStore.tryAcquire(threadId);

            // ===== 1. 抢 Turn 租约（等待期发 SSE comment，协议安全且不产生游标） =====
            long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT.toMillis();
            while (token == null && System.currentTimeMillis() < deadline) {
                sink.next(commentFrame());
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sink.complete();
                    return;
                }
                token = turnLeaseStore.tryAcquire(threadId);
            }
            if (token == null) {
                sink.next(runErrorSSE(threadId, runId,
                    "turn_in_progress: session '" + threadId + "' has an active turn"));
                sink.complete();
                return;
            }

            // ===== 2. ASKING 预检（与 /chat 同源；挂起会话需经 resume[] 恢复） =====
            if (runtimeService.hasPendingConfirm(threadId)) {
                turnLeaseStore.release(threadId, token);
                sink.next(runErrorSSE(threadId, runId,
                    "turn_pending_confirm: session '" + threadId
                        + "' is in ASKING state; send resume[] to resolve the pending interrupts"));
                sink.complete();
                return;
            }

            // ===== 3. 续租 guard =====
            TurnLeaseGuard lease;
            try {
                lease = new TurnLeaseGuard(turnLeaseStore, threadId, token);
            } catch (Exception e) {
                log.error("[agui] failed to start lease renewer, releasing lease (sid={}): {}",
                    threadId, e.getMessage());
                turnLeaseStore.release(threadId, token);
                sink.next(runErrorSSE(threadId, runId,
                    "turn_setup_failed: " + (e.getMessage() != null
                        ? e.getMessage() : e.getClass().getSimpleName())));
                sink.complete();
                return;
            }

            var turnEnded = new AtomicBoolean(false);
            var toolSummary = new TurnToolSummaryTracker(threadId, runId,
                (type, payload) -> emitSyntheticAgui(threadId, runId, type, payload));
            // run 级编码上下文：blockId→messageId 映射、token 累计、pending interrupts
            // 都是 run 级状态——必须跨事件复用，不能逐事件新建
            var codecCtx = codec.newContext(threadId, runId);
            var renderer = new AguiTailRenderer(threadId, runId);
            // 本 turn 触及的桶 key（toolCallId），收尾统一清理（A4，与 /chat 同模式）
            var turnBucketKeys = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();

            try {
                eventBus.beginTurn(threadId);

                // ===== 4. 构造消息（@Skill 注入 + 会话元数据，与 /chat 同构） =====
                var processedMessage = skillInjectionService.injectSkillReferences(text, userId);
                var msgMeta = new java.util.HashMap<String, Object>();
                msgMeta.put(UiContextStore.METADATA_SESSION_KEY, threadId);
                var msg = Msg.builder().role(MsgRole.USER).name(userId)
                    .metadata(msgMeta)
                    .textContent(processedMessage).build();

                // ===== 5. 先订阅 EventBus（响应流 = 存储流的渲染，实时/回放零漂移） =====
                var sub = eventBus.subscribeEnveloped(threadId, 0, runId);
                sub.events()
                    .flatMapIterable(renderer::render)
                    .mergeWith(heartbeatFlux().takeUntilOther(sub.completion()))
                    .subscribe(
                        sink::next,
                        e -> {
                            log.warn("[agui] event bus subscription error (sid={}): {}",
                                threadId, e.getMessage());
                            sink.error(e);
                        },
                        sink::complete);

                // ===== 6. 启动 agent 执行（同 ChatUiChannel 网关路径，会话身份与 native 面同源） =====
                var agent = runtimeService.getAgent(threadId);
                if (agent == null) {
                    throw new IllegalStateException("no active agent");
                }
                var channel = agent.channel(ChatUiChannel.perPeer());
                channel.sendStream(ChatUiRequest.withPeer(threadId, List.of(msg)))
                    .subscribe(
                        event -> handleEventAndEmit(event, threadId, runId, lease, sink,
                            turnEnded, toolSummary, codecCtx, turnBucketKeys),
                        e -> {
                            log.warn("[agui] run stream error (sid={}): {}", threadId, e.getMessage());
                            if (!lease.isLost()) {
                                var errText = e.getMessage() != null
                                    ? e.getMessage() : e.getClass().getSimpleName();
                                emitSyntheticAgui(threadId, runId, "error",
                                    "{\"type\":\"error\",\"error\":"
                                        + AgentEventSseSerializer.jsonEsc(errText) + "}");
                            }
                            endTurnAndCleanup(lease, threadId, turnEnded, turnBucketKeys);
                        },
                        () -> endTurnAndCleanup(lease, threadId, turnEnded, turnBucketKeys));
            } catch (Exception e) {
                log.warn("[agui] run setup failed, rolling back (sid={}): {}", threadId, e.getMessage());
                TurnFinalizer.abortSetup(eventBus, lease, sink, threadId,
                    runErrorSSE(threadId, runId, "turn_setup_failed: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                return;
            }

            // ===== 7. onCancel：断连不杀任务（事件照常落库，客户端经 /events 续传） =====
            sink.onCancel(() -> log.info("[agui] SSE disconnected, run continues (sid={}, rid={})",
                threadId, runId));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // ===== HITL 恢复轮（镜像 ConfirmController.confirm-stream） =====

    private Flux<ServerSentEvent<String>> resumeRun(String threadId, String runId,
                                                    List<AguiResumeDTO> resumes) {
        return Flux.<ServerSentEvent<String>>create(sink -> {
            // ===== 1. resume[] → native ConfirmRequest results（acceptRule 平台扩展透传） =====
            List<Map<String, Object>> results;
            try {
                results = translateResumes(resumes);
            } catch (IllegalArgumentException e) {
                sink.next(runErrorSSE(threadId, runId, "invalid_resume: " + e.getMessage()));
                sink.complete();
                return;
            }

            // ===== 2. 预检确认上下文（not_found / already_consumed 语义 → RUN_ERROR，先于租约） =====
            try {
                runtimeService.checkConfirmAvailable(threadId);
            } catch (Exception e) {
                sink.next(runErrorSSE(threadId, runId, e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName()));
                sink.complete();
                return;
            }

            // ===== 3. 抢租约（带超时排队；超时 → RUN_ERROR） =====
            var token = turnLeaseStore.acquire(threadId, ACQUIRE_WAIT);
            if (token == null) {
                sink.next(runErrorSSE(threadId, runId,
                    "turn_in_progress: session '" + threadId + "' has an active turn"));
                sink.complete();
                return;
            }
            var lease = new TurnLeaseGuard(turnLeaseStore, threadId, token);

            var turnEnded = new AtomicBoolean(false);
            var toolSummary = new TurnToolSummaryTracker(threadId, runId,
                (type, payload) -> emitSyntheticAgui(threadId, runId, type, payload));
            var codecCtx = codec.newContext(threadId, runId);
            var renderer = new AguiTailRenderer(threadId, runId);

            // 播种已确认的工具调用：SDK ToolResultEventConverter 有 hasStartedToolCall 守卫
            //（TOOL_RESULT 事件要求同 run 上下文里有过 TOOL_CALL_START），而 HITL 恢复段
            // 不重发 TOOL_CALL_START（与 native confirm-stream 同语义）。此处按 pending
            // 确认上下文预登记后立即 drain 丢弃种子帧——客户端在原 run 已渲染过该调用，
            // 恢复段只需 TOOL_CALL_RESULT 正常产出。
            var pending = runtimeService.findPendingConfirm(threadId);
            if (pending != null) {
                @SuppressWarnings("unchecked")
                var tools = (List<Map<String, Object>>) pending.get("tools");
                if (tools != null) {
                    for (var tc : tools) {
                        var tcId = String.valueOf(tc.get("tool_call_id"));
                        boolean confirmedThisRun = results.stream()
                            .anyMatch(r -> tcId.equals(String.valueOf(r.get("tool_call_id"))));
                        if (confirmedThisRun) {
                            codecCtx.startToolCall(tcId, String.valueOf(tc.get("name")));
                            codecCtx.drainEvents();
                        }
                    }
                }
            }

            Flux<AgentEvent> resumeFlux;
            try {
                eventBus.beginTurn(threadId);
                // 冷流：CAS 消费确认上下文（confirm_already_consumed → RUN_ERROR）在此发生
                resumeFlux = runtimeService.resumeWithConfirmEvents(threadId, null, results);
            } catch (Exception e) {
                log.warn("[agui] resume setup failed, rolling back (sid={}): {}", threadId, e.getMessage());
                TurnFinalizer.abortSetup(eventBus, lease, sink, threadId,
                    runErrorSSE(threadId, runId, e.getMessage() != null
                        ? e.getMessage() : e.getClass().getSimpleName()));
                return;
            }

            var sub = eventBus.subscribeEnveloped(threadId, 0, runId);
            sub.events()
                .flatMapIterable(renderer::render)
                .mergeWith(heartbeatFlux().takeUntilOther(sub.completion()))
                .subscribe(
                    sink::next,
                    e -> {
                        log.warn("[agui] event bus subscription error on resume (sid={}): {}",
                            threadId, e.getMessage());
                        sink.error(e);
                    },
                    sink::complete);

            resumeFlux.subscribe(
                event -> handleEventAndEmit(event, threadId, runId, lease, sink,
                    turnEnded, toolSummary, codecCtx,
                    java.util.concurrent.ConcurrentHashMap.newKeySet()),
                e -> {
                    log.warn("[agui] resume stream error (sid={}): {}", threadId, e.getMessage());
                    if (!lease.isLost()) {
                        var errText = e.getMessage() != null
                            ? e.getMessage() : e.getClass().getSimpleName();
                        emitSyntheticAgui(threadId, runId, "error",
                            "{\"type\":\"error\",\"error\":"
                                + AgentEventSseSerializer.jsonEsc(errText) + "}");
                    }
                    TurnFinalizer.endTurn(eventBus, lease, threadId, turnEnded);
                },
                () -> TurnFinalizer.endTurn(eventBus, lease, threadId, turnEnded));

            sink.onCancel(() -> log.info(
                "[agui] SSE disconnected on resume, agent continues (sid={}, rid={})",
                threadId, runId));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * resume[] → native results[]：interruptId（runId:toolCallId）按最后一个 ':' 解析回
     * toolCallId；status=resolved 且 payload.approved===true（严格布尔，官方语义）→
     * confirmed=true，其余（approved=false / cancelled / 缺失）→ confirmed=false；
     * payload.acceptRule=true（平台扩展，官方客户端不传）→ accept_rule 透传。
     */
    static List<Map<String, Object>> translateResumes(List<AguiResumeDTO> resumes) {
        var results = new ArrayList<Map<String, Object>>();
        for (var r : resumes) {
            if (r == null || r.interruptId() == null || r.interruptId().isBlank()) {
                throw new IllegalArgumentException("resume.interruptId is required");
            }
            var interruptId = r.interruptId();
            int sep = interruptId.lastIndexOf(':');
            if (sep <= 0 || sep == interruptId.length() - 1) {
                throw new IllegalArgumentException(
                    "resume.interruptId must be '<runId>:<toolCallId>': " + interruptId);
            }
            var toolCallId = interruptId.substring(sep + 1);
            boolean approved = false;
            if ("resolved".equalsIgnoreCase(r.status()) && r.payload() != null) {
                approved = Boolean.TRUE.equals(r.payload().get("approved"));
            }
            boolean acceptRule = r.payload() != null
                && Boolean.TRUE.equals(r.payload().get("acceptRule"));
            var row = new LinkedHashMap<String, Object>();
            row.put("tool_call_id", toolCallId);
            row.put("confirmed", approved);
            row.put("accept_rule", acceptRule);
            results.add(row);
        }
        return results;
    }

    // ===== GET /agui/threads/{threadId}/events（平台扩展续传端点） =====

    @GetMapping(value = "/threads/{threadId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(
            @PathVariable String threadId,
            @RequestParam(value = "afterSeq", defaultValue = "0") int afterSeq,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        var sid = PathSafe.sanitize(threadId);
        if (!io.agentmanager.framework.service.SessionAccessGuard.ownedBy(
                sessionUserStore, sid, headerUserId)) {
            // 与 /subscribe 同口径：归属校验失败 404（同步抛，SSE 头未提交）
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Session '" + sid + "' not found");
        }
        var rid = runId == null || runId.isBlank() ? null : PathSafe.sanitize(runId);
        var renderer = new AguiTailRenderer(sid, rid == null ? "" : rid);
        return tailer.tail(sid, rid, afterSeq, renderer);
    }

    // ===== 事件处理（镜像 ChatStreamController.handleEventAndEmit，含双编码） =====

    private void handleEventAndEmit(AgentEvent event, String sessionId, String replyId,
                                    TurnLeaseGuard lease, FluxSink<ServerSentEvent<String>> sink,
                                    AtomicBoolean turnEnded, TurnToolSummaryTracker toolSummary,
                                    AguiStreamContext codecCtx,
                                    java.util.Set<String> turnBucketKeys) {
        // 租约丢失：停写 + 本连接 RUN_ERROR（不走 TurnFinalizer——其终态帧是 native 词表，
        // 本面客户端需要 AG-UI 语义的帧）
        if (stopIfLeaseLost(lease, sessionId, replyId, sink)) {
            return;
        }

        audit(event, sessionId);

        if (event instanceof ToolCallStartEvent start
                && start.getToolCallId() != null && start.getToolCallName() != null) {
            turnBucketKeys.add(start.getToolCallId());
            toolCallNames.put(start.getToolCallId(), start.getToolCallName());
        }

        // present_file/present_url 结果累积（file_ready 合成的数据源，与 /chat 同构）
        if (event instanceof ToolResultTextDeltaEvent trd
                && (isTool(trd.getToolCallId(), "present_file", trd.getToolCallName())
                    || isTool(trd.getToolCallId(), "present_url", trd.getToolCallName()))) {
            turnBucketKeys.add(trd.getToolCallId());
            accumulatePresentFile(trd.getToolCallId(), String.valueOf(trd.getDelta()));
        }

        // 工具摘要累积（tool_call_summary/tool_result_preview 合成帧）
        if (event instanceof ToolCallStartEvent start) {
            toolSummary.onToolCallStart(start);
        } else if (event instanceof ToolCallDeltaEvent delta) {
            toolSummary.onToolCallDelta(delta);
        } else if (event instanceof ToolResultTextDeltaEvent trd) {
            toolSummary.onToolResultDelta(trd);
        }

        // HITL：确认上下文同源落库（双面互认的根基）；interrupt 由 codec 的
        // PermissionConfirmConverter 登记、RUN_FINISHED 随 AGENT_END 统一携带
        if (event instanceof RequireUserConfirmEvent) {
            log.info("[agui] HITL permission_ask: sessionId={}, tools={}", sessionId,
                ((RequireUserConfirmEvent) event).getToolCalls().stream().map(tc -> tc.getName()).toList());
            runtimeService.storeConfirmContext(sessionId, event);
        }

        // 双编码发射：p=native 词表；pa=AG-UI 帧（codec 失败降级 null → §6.3）
        String pa = codec.encode(event, codecCtx);
        var payloadOverride = payloadForEvent(event);
        eventBus.emitAgui(sessionId, event, replyId, payloadOverride, pa);

        if (event instanceof ToolCallEndEvent e) {
            toolSummary.onToolCallEnd(e);
        } else if (event instanceof ToolResultEndEvent e) {
            toolSummary.onToolResultEnd(e);
        }

        // file_ready 合成（产出文件工具完成；CUSTOM 对等承载）
        if (event instanceof ToolResultEndEvent tre
                && ("present_file".equals(tre.getToolCallName())
                    || "present_url".equals(tre.getToolCallName()))) {
            emitFileReady(sessionId, replyId, tre.getToolCallId());
        }

        // AGENT_END（仅 lead 自身）：turn 终点 → RUN_FINISHED（codec 在 pa 已携带
        // interrupts outcome）随本帧落库，endTurn 收尾（先刷缓冲再放锁，与 /chat 同序）
        if (event.getType() == AgentEventType.AGENT_END
                && !AgentEventSseSerializer.isRemoteForwarded(event)) {
            log.info("[agui] run completed: sessionId={}", sessionId);
            endTurnAndCleanup(lease, sessionId, turnEnded, turnBucketKeys);
        }
    }

    /** turn 收尾 + 本 turn 桶清理（A4：首胜才清桶；与 ChatStreamController 同模式） */
    private void endTurnAndCleanup(TurnLeaseGuard lease, String sessionId,
                                   AtomicBoolean turnEnded, java.util.Set<String> turnBucketKeys) {
        if (!TurnFinalizer.endTurn(eventBus, lease, sessionId, turnEnded)) {
            return;
        }
        for (String toolCallId : turnBucketKeys) {
            toolCallNames.remove(toolCallId);
            presentFileBuffers.remove(toolCallId);
        }
    }

    /**
     * 租约丢失处置（AG-UI 语义版 TurnFinalizer.stopIfLeaseLost）：终态帧改发 RUN_ERROR
     * （native 版发 {"type":"interrupted"} 帧，与本面词表不符）；其余副作用一致——
     * 不落库、abandonSession 丢弃缓冲、release。
     */
    private boolean stopIfLeaseLost(TurnLeaseGuard lease, String sessionId, String runId,
                                    FluxSink<ServerSentEvent<String>> sink) {
        if (!lease.isLost()) {
            return false;
        }
        if (lease.tryMarkLostNotified()) {
            log.error("[agui] turn lease lost, stopping writer (sid={})", sessionId);
            sink.next(runErrorSSE(sessionId, runId,
                "turn_lease_lost: this replica lost the turn lease; reconnect to /agui/threads/"
                    + sessionId + "/events for the takeover output"));
            eventBus.abandonSession(sessionId);
            lease.release();
        }
        return true;
    }

    /** MCP Apps ui 元数据（native payloadOverride，与 /chat 同源数据） */
    private String payloadForEvent(AgentEvent event) {
        if (event instanceof ToolCallStartEvent tc) {
            var uiRef = mcpToolRegistrar.resolveUiRef(tc.getToolCallName());
            if (uiRef != null) {
                return AgentEventSseSerializer.payload(event, uiRef.resourceUri(), uiRef.serverName());
            }
        }
        return null;
    }

    /** 合成帧双编码发射：p 帧原文 + pa=CUSTOM（name 同帧名，value=p 解析值） */
    private void emitSyntheticAgui(String sessionId, String replyId, String type, String payload) {
        Object value = null;
        try {
            value = JSON.readTree(payload);
        } catch (Exception ignored) {
            // value 保持 null，pa 仍下发（name 可路由）
        }
        String pa = codec.encodeCustom(sessionId, replyId, type, value);
        eventBus.emitSyntheticAgui(sessionId, replyId, type, payload, pa);
    }

    private void emitFileReady(String sessionId, String replyId, String toolCallId) {
        var buf = presentFileBuffers.remove(toolCallId);
        if (buf == null) return;
        String json;
        synchronized (buf) { json = buf.toString(); }
        try {
            var node = JSON.readTree(json);
            if (node != null && node.isTextual()) {
                node = JSON.readTree(node.asText());
            }
            if (node == null || !node.isObject() || !node.has("file_id")) {
                log.warn("[agui] file tool result missing file_id, skip file_ready (sid={})", sessionId);
                return;
            }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "file_ready");
            payload.put("file_id", node.get("file_id").asText());
            payload.put("file_name", node.has("file_name") && !node.get("file_name").isNull()
                ? node.get("file_name").asText() : "file");
            payload.put("mime_type", node.has("mime_type") && !node.get("mime_type").isNull()
                ? node.get("mime_type").asText() : null);
            payload.put("size", node.has("size") ? node.get("size").asLong() : 0);
            payload.put("download_url", "/files/" + node.get("file_id").asText());
            emitSyntheticAgui(sessionId, replyId, "file_ready",
                AgentEventSseSerializer.payload(payload));
        } catch (Exception e) {
            log.warn("[agui] file_ready synthesis failed: {}", e.getMessage());
        }
    }

    private void accumulatePresentFile(String toolCallId, String delta) {
        if (toolCallId == null) return;
        var buf = presentFileBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
        synchronized (buf) {
            int room = PRESENT_FILE_BUFFER_MAX - buf.length();
            if (room <= 0) {
                return;
            }
            buf.append(delta.length() > room ? delta.substring(0, room) : delta);
        }
    }

    private boolean isTool(String toolCallId, String expected, String eventToolName) {
        if (toolCallId == null) {
            return false;
        }
        String registered = toolCallNames.get(toolCallId);
        return expected.equals(registered != null ? registered : eventToolName);
    }

    private void audit(AgentEvent event, String sessionId) {
        try {
            if (event instanceof ToolCallStartEvent tc) {
                toolAuditStore.record(sessionId, tc.getToolCallName(), tc.getToolCallId(),
                    "TOOL_CALL_START", AgentEventSseSerializer.payload(event));
            } else if (event instanceof ToolCallEndEvent tc) {
                toolAuditStore.record(sessionId, tc.getToolCallName(), tc.getToolCallId(),
                    "TOOL_CALL_END", AgentEventSseSerializer.payload(event));
            } else if (event instanceof ToolResultEndEvent tc) {
                toolAuditStore.record(sessionId, tc.getToolCallName(), tc.getToolCallId(),
                    "TOOL_RESULT_END", AgentEventSseSerializer.payload(event));
            }
        } catch (Exception e) {
            log.debug("audit record skipped (sid={}): {}", sessionId, e.getMessage());
        }
    }

    // ===== SSE 工厂 =====

    private static ServerSentEvent<String> commentFrame() {
        return ServerSentEvent.<String>builder().comment("hb").build();
    }

    private Flux<ServerSentEvent<String>> heartbeatFlux() {
        return Flux.interval(heartbeatInterval)
            .map(i -> commentFrame());
    }

    /** RUN_ERROR 帧（编码失败时退化为最小 JSON，保证错误语义可达） */
    static ServerSentEvent<String> runErrorSSE(String threadId, String runId, String message) {
        String data;
        try {
            data = ENCODER.encodeToJson(
                new io.agentscope.core.agui.event.AguiEvent.RunError(threadId, runId,
                    message, "PLATFORM_ERROR"));
        } catch (Exception e) {
            data = "{\"type\":\"RUN_ERROR\",\"threadId\":\"" + threadId
                + "\",\"runId\":\"" + runId + "\",\"message\":"
                + AgentEventSseSerializer.jsonEsc(message) + "}";
        }
        return ServerSentEvent.<String>builder().data(data).build();
    }

    // ===== 私有可测性观察点 =====

    /** 两个工具桶条目数之和（测试断言「turn 收尾清桶」用） */
    int bucketEntryCount() {
        return toolCallNames.size() + presentFileBuffers.size();
    }
}
