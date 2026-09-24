package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.SkillInjectionService;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseGuard;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentmanager.framework.service.UiContextStore;
import io.agentmanager.framework.service.UploadWorkspaceInjector;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * 对话入口端点 &mdash; sessionId 在请求体中，可选。
 *
 * <p>本工程唯一对话入口：
 * <ul>
 *   <li>{@code POST /threads/chat} &mdash; sessionId 在请求体中，可选</li>
 *   <li>不传 sessionId 时自动生成 UUID，首个 SSE 事件为 {@code session_created}</li>
 *   <li>传了 sessionId 则续接已有会话</li>
 * </ul>
 *
 * <p>首次调用无需预知 sessionId：
 * <pre>{@code
 * // 第一次：不传 sessionId
 * POST /threads/chat  { "message": "你好" }
 * <- SSE: {"type":"session_created","session_id":"abc-123"}
 * <- SSE: {"type":"text_delta",...}
 * <- SSE: {"type":"done"}
 *
 * // 后续：传 sessionId 续接
 * POST /threads/chat  { "message": "继续", "sessionId": "abc-123" }
 * }</pre>
 *
 * <p>其余端点（subscribe / status / confirm / history）仍需 sessionId 在路径中
 * （见 {@link SessionStreamController}），因为它们是面向已知会话的操作。
 */
@RestController
@RequestMapping("/threads")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    static Duration WAITING_FRAME_INTERVAL = Duration.ofSeconds(15);
    static Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(120);

    private final ChatUiChannel chatChannel;
    private final AgentRuntimeService runtimeService;
    private final TurnLeaseStore turnLeaseStore;
    private final ToolAuditStore toolAuditStore;
    private final UploadWorkspaceInjector workspaceInjector;
    private final SandboxConfig sandboxConfig;
    private final SessionEventBus eventBus;
    private final SessionEventStore eventStore;
    private final SessionUserStore sessionUserStore;
    private final WorkspaceReader workspaceReader;
    private final AgentManagerProperties props;
    private final SkillInjectionService skillInjectionService;
    private final io.agentmanager.framework.service.FileAssetStore fileAssetStore;
    private final McpToolRegistrar mcpToolRegistrar;

    /** 孤儿 turn 看护（独立调度线程：绝不在 Reactor 回调线程上阻塞） */
    private final OrphanTurnWatchdog orphanTurnWatchdog = new OrphanTurnWatchdog();

    /** 关闭时释放看护线程池 */
    @jakarta.annotation.PreDestroy
    void shutdownWatchdog() {
        orphanTurnWatchdog.shutdown();
    }

    /** 产出文件工具（present_file/present_url）结果文本累积（toolCallId &rarr; 文本桶，64KB 上限防内存膨胀） */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> presentFileBuffers =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** write_file 工具输入参数累积（toolCallId &rarr; JSON 片段桶），用于截获 path+content 后同步 KV */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> writeFileInputBuffers =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * toolCallId &rarr; 真实工具名（turn 内有效）。
     *
     * <p>SDK 只在 {@link ToolCallStartEvent} 上携带真实工具名——后续的
     * {@code ToolCallDeltaEvent} 虽然也有 getToolCallName()，实测恒为占位符
     * {@code "__fragment__"}（参数分片帧，见 e2e-ci-plan §11.3 D3）。
     * 因此凡需按工具名分派的逻辑，必须先在此登记、后续查表，不能直接读 delta 的名字。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> toolCallNames =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final int PRESENT_FILE_BUFFER_MAX = 64 * 1024;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    public ChatStreamController(ChatUiChannel chatChannel,
                                AgentRuntimeService runtimeService,
                                TurnLeaseStore turnLeaseStore,
                                ToolAuditStore toolAuditStore,
                                UploadWorkspaceInjector workspaceInjector,
                                SandboxConfig sandboxConfig,
                                SessionEventBus eventBus,
                                SessionEventStore eventStore,
                                SessionUserStore sessionUserStore,
                                WorkspaceReader workspaceReader,
                                AgentManagerProperties props,
                                SkillInjectionService skillInjectionService,
                                io.agentmanager.framework.service.FileAssetStore fileAssetStore,
                                McpToolRegistrar mcpToolRegistrar) {
        this.chatChannel = chatChannel;
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
        this.toolAuditStore = toolAuditStore;
        this.workspaceInjector = workspaceInjector;
        this.sandboxConfig = sandboxConfig;
        this.eventBus = eventBus;
        this.eventStore = eventStore;
        this.sessionUserStore = sessionUserStore;
        this.workspaceReader = workspaceReader;
        this.props = props;
        this.skillInjectionService = skillInjectionService;
        this.fileAssetStore = fileAssetStore;
        this.mcpToolRegistrar = mcpToolRegistrar;
    }

    /**
     * 发起对话（sessionId 在请求体，可选）。
     *
     * <p>请求体示例：
     * <pre>{@code
     * {
     *   "message": "你好",
     *   "userId": "user-123",          // 可选
     *   "sessionId": "my-session-1",   // 可选，不传则自动生成
     *   "fileIds": []                  // 可选
     * }
     * }</pre>
     *
     * <p>不传 sessionId 时，首个 SSE 事件为：
     * <pre>{@code {"type":"session_created","session_id":"uuid-xxx"}}</pre>
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest body,
                                              @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        var message = body.message();
        if ((message == null || message.isBlank()) && (body.fileIds() == null || body.fileIds().isEmpty())) {
            return Flux.just(errorSSE("message or fileIds is required"));
        }

        // 网关 Header 优先 > 请求体 userId > 默认 debug-user
        var userId = (headerUserId != null && !headerUserId.isBlank())
            ? headerUserId
            : (body.userId() != null && !body.userId().isBlank()) ? body.userId() : "debug-user";

        // ★ 核心：sessionId 在请求体中，可选；不传则自动生成 UUID
        boolean isNewSession = body.sessionId() == null || body.sessionId().isBlank();
        var sessionId = isNewSession
            ? UUID.randomUUID().toString()
            : body.sessionId();

        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        userId = io.agentmanager.framework.util.PathSafe.sanitize(userId);

        log.info("[chat] start: sessionId={}, userId={}, isNew={}, messageLen={}, fileCount={}",
            sessionId, userId, isNewSession,
            message != null ? message.length() : 0,
            body.fileIds() != null ? body.fileIds().size() : 0);

        String finalSessionId = sessionId;
        String finalUserId = userId;
        boolean emitSessionCreated = isNewSession;

        // 记录会话-用户映射
        sessionUserStore.upsert(finalSessionId, finalUserId);

        return Flux.<ServerSentEvent<String>>create(sink -> {
            // ===== 0. 新会话：首个 SSE 事件通知前端 session_id =====
            if (emitSessionCreated) {
                sink.next(ServerSentEvent.<String>builder()
                    .data("{\"type\":\"session_created\",\"session_id\":"
                        + AgentEventSseSerializer.jsonEsc(finalSessionId) + "}")
                    .build());
            }

            // ===== 1. 抢 Turn 租约 =====
            var token = turnLeaseStore.tryAcquire(finalSessionId);
            long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT.toMillis();
            while (token == null && System.currentTimeMillis() < deadline) {
                sink.next(waitingSSE());
                try {
                    Thread.sleep(WAITING_FRAME_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sink.complete();
                    return;
                }
                token = turnLeaseStore.tryAcquire(finalSessionId);
            }
            if (token == null) {
                log.warn("[chat] turn_in_progress: sessionId={}, waiting timeout", finalSessionId);
                sink.next(errorSSE("turn_in_progress: session '" + finalSessionId
                    + "' has an active turn and queue timeout reached"));
                sink.complete();
                return;
            }

            // ===== 2. 启动续租 =====
            // 构造失败必须显式释放：guard 的构造里若在起线程之前就抛了（判据计算、线程池创建），
            // 没有任何人持有 token，而下面那段的 catch 也覆盖不到它。没有续租线程时租约会在
            // TTL 后自然过期，不会永久锁死，但那是「靠超时自愈」，不该作为正常回滚手段。
            TurnLeaseGuard lease;
            try {
                lease = new TurnLeaseGuard(turnLeaseStore, finalSessionId, token);
            } catch (Exception e) {
                log.error("[chat] failed to start lease renewer, releasing lease (sid={}): {}",
                    finalSessionId, e.getMessage());
                turnLeaseStore.release(finalSessionId, token);
                sink.next(errorSSE("turn_setup_failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                sink.complete();
                return;
            }

            // ===== 3. 准备 EventBus Sinks =====
            String replyId = UUID.randomUUID().toString();
            // 为何本 turn 只收尾一次（迟到的 complete/error 不得再拆 sink）：
            // 见 TurnFinalizer#endTurn 的教训注释
            var turnEnded = new java.util.concurrent.atomic.AtomicBoolean(false);
            // 本 turn 触及过的桶 key（toolCallId）；turn 收尾时统一清桶，防异常路径残留。
            // 按 turn 粒度隔离：清理只删本 turn 登记过的 key，toolCallId 跨 session 不可能
            // 撞 key，并行 turn 的桶不受影响（A4）
            var turnBucketKeys = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();

            // 准备段异常的回滚（error 帧 → closeSession → release → complete）为何必须做：
            // 见 TurnFinalizer#abortSetup 的教训注释。
            // 该路径 replyId 尚未产出任何事件、无本 turn 桶可清，turnBucketKeys 必为空
            List<Msg> messages;
            try {
                eventBus.beginTurn(finalSessionId);

                // ===== 4. 构造消息 =====
                // 注入 @Skill 引用
                var processedMessage = skillInjectionService.injectSkillReferences(message);

                if (body.fileIds() != null && !(sandboxConfig != null && sandboxConfig.enabled())) {
                    for (var fileId : body.fileIds()) {
                        workspaceInjector.injectToWorkspace(fileId, finalSessionId);
                    }
                }
                var blocks = workspaceInjector.buildContentBlocks(body.fileIds(), processedMessage, finalUserId);
                var msg = Msg.builder().role(MsgRole.USER).name(finalUserId)
                    .metadata(Map.of(UiContextStore.METADATA_SESSION_KEY, finalSessionId))
                    .content(blocks).build();
                messages = new ArrayList<>();
                messages.add(msg);

                // ===== 5. 先订阅 EventBus =====
                eventBus.subscribe(finalSessionId, 0, replyId)
                    .subscribe(
                        sse -> sink.next(sse),
                        e -> {
                            log.warn("EventBus subscription error (sid={}): {}", finalSessionId, e.getMessage());
                            sink.error(e);
                        },
                        () -> sink.complete());

                // ===== 6. 启动 agent 执行 =====
                // sendStream 的**同步**异常也必须落在这个 try 里：它抛之前租约已经到手，
                // 而续租线程不看本段是否还活着——漏放租约就是该 session 被永久锁死
                // （后续每个请求都拿不到租约，观察者也会一直 probe 到 RUNNING）。
                chatChannel.sendStream(ChatUiRequest.withPeer(finalSessionId, messages))
                    .subscribe(
                        event -> handleEventAndEmit(event, finalSessionId, replyId, lease,
                            finalUserId, sink, turnEnded, turnBucketKeys),
                        e -> {
                            log.warn("session chat stream error (sid={}): {}", finalSessionId, e.getMessage());
                            if (isTrailingSandboxTeardownError(e)) {
                                log.info("ignore trailing sandbox teardown error (sid={})", finalSessionId);
                            } else if (!lease.isLost()) {
                                // 丢锁后这场 error 多半是丢锁的后果，再落一条只会占用新 owner 的 seq
                                eventBus.emitSynthetic(finalSessionId, replyId, "error",
                                    "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(
                                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "}");
                            }
                            endTurnAndCleanupBuckets(lease, finalSessionId, turnEnded, turnBucketKeys);
                        },
                        () -> endTurnAndCleanupBuckets(lease, finalSessionId, turnEnded, turnBucketKeys));
            } catch (Exception e) {
                log.warn("[chat] turn setup failed, rolling back (sid={}): {}",
                    finalSessionId, e.getMessage());
                TurnFinalizer.abortSetup(eventBus, lease, sink, finalSessionId,
                    errorSSE("turn_setup_failed: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                return;
            }

            // ===== 7. onCancel =====
            // 断连不杀任务（durable-sse 不变量）：agent 继续跑、租约保留、事件照常落库，
            // 客户端可用 /subscribe 续传；turn 终态由源 flux 的 complete/error 回调收尾。
            //
            // 注意（2026-09-19 教训）：此处**绝不能做阻塞操作**——onCancel 跑在 Reactor 的
            // 取消回调线程（boundedElastic）上，任何 sleep/同步等待都会占死该线程并连带
            // 卡住 SDK 会话闸门（LocalSessionTurnGate）的释放路径，导致后续所有 turn 全堵。
            // 需要兜底看护时只能用独立调度线程，见 OrphanTurnWatchdog。
            sink.onCancel(() -> {
                log.info("[chat] SSE disconnected, agent execution continues (sid={}, rid={})",
                    finalSessionId, replyId);
                orphanTurnWatchdog.schedule(() -> {
                    if (!turnEnded.get()) {
                        log.warn("[chat] orphan turn grace expired ({}ms), force-releasing lease "
                            + "(sid={}, rid={}) —— 防租约/闸门许可泄漏",
                            OrphanTurnWatchdog.GRACE_MS, finalSessionId, replyId);
                        endTurnAndCleanupBuckets(lease, finalSessionId, turnEnded, turnBucketKeys);
                    }
                }, finalSessionId, replyId);
            });

        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // ===== 事件处理 =====

    /**
     * TOOL_CALL_START 命中 MCP App ui 映射时，序列化携带 ui 元数据
     * （mcp-apps-extension-plan §4：发源地查 {@link McpToolRegistrar} 后传入；
     * 前端 chat.js 据此把工具行渲染为 iframe 内嵌的 MCP App 卡片）。
     * 非 ui 工具（含内置工具）返回 null，payload 保持原词表。
     */
    private String payloadForEvent(AgentEvent event) {
        if (event instanceof ToolCallStartEvent tc) {
            var uiRef = mcpToolRegistrar.resolveUiRef(tc.getToolCallName());
            if (uiRef != null) {
                return AgentEventSseSerializer.payload(event, uiRef.resourceUri(), uiRef.serverName());
            }
        }
        return null;
    }

    private void handleEventAndEmit(AgentEvent event, String sessionId,
                                    String replyId, TurnLeaseGuard lease, String userId,
                                    FluxSink<ServerSentEvent<String>> sink,
                                    java.util.concurrent.atomic.AtomicBoolean turnEnded,
                                    java.util.Set<String> turnBucketKeys) {
        // 为何丢租约必须立刻停写、终态帧为何不落库：见 TurnFinalizer#stopIfLeaseLost
        if (TurnFinalizer.stopIfLeaseLost(eventBus, lease, sessionId, sink, "[chat]")) {
            return;
        }

        audit(event, sessionId);

        // 工具名登记：ToolCallStartEvent 是唯一携带真实工具名的事件（delta 帧的名字是占位符）。
        // 同点登记 turnBucketKeys（A4）：新增桶 put 点时必须在此同步登记，否则该类残留
        // 回到「异常路径跨 turn 累积」的现状
        if (event instanceof ToolCallStartEvent start
                && start.getToolCallId() != null && start.getToolCallName() != null) {
            turnBucketKeys.add(start.getToolCallId());
            toolCallNames.put(start.getToolCallId(), start.getToolCallName());
        }

        // write_file 输入参数截获（按登记的真实工具名判定，不能读 delta.getToolCallName()）
        if (!sandboxConfig.enabled()) {
            if (event instanceof ToolCallDeltaEvent delta
                    && isTool(delta.getToolCallId(), "write_file")) {
                turnBucketKeys.add(delta.getToolCallId());
                accumulateWriteFileInput(delta.getToolCallId(), String.valueOf(delta.getDelta()));
            }
            if (event instanceof ToolCallEndEvent end && isTool(end.getToolCallId(), "write_file")) {
                syncWriteFileToKv(end.getToolCallId(), userId);
                toolCallNames.remove(end.getToolCallId());
            }
        }

        // 产出文件工具（present_file/present_url）结果累积（同样按登记名判定）
        if (event instanceof ToolResultTextDeltaEvent trd
                && (isTool(trd.getToolCallId(), "present_file", trd.getToolCallName())
                    || isTool(trd.getToolCallId(), "present_url", trd.getToolCallName()))) {
            turnBucketKeys.add(trd.getToolCallId());
            accumulatePresentFile(trd.getToolCallId(), String.valueOf(trd.getDelta()));
        }

        // HITL
        if (event instanceof RequireUserConfirmEvent) {
            log.info("[chat] HITL permission_ask: sessionId={}, tools={}", sessionId,
                ((RequireUserConfirmEvent) event).getToolCalls().stream()
                    .map(tc -> tc.getName()).toList());
            runtimeService.storeConfirmContext(sessionId, event);
            lease.release();
        }

        eventBus.emit(sessionId, event, replyId, payloadForEvent(event));

        // HITL 是 turn 边界：permission_ask 已广播，关闭 sink 让订阅者正常结束。
        // 必须在 emit 之后——否则订阅者收不到 permission_ask（durable-sse-multinode-plan §3.4.4）。
        if (event instanceof RequireUserConfirmEvent) {
            eventBus.closeSession(sessionId);
        }

        // 产出文件工具完成合成 file_ready（present_url 与 present_file 返回同构 JSON，
        // 复用同一条下载卡片链路；外部交付物经 /files/{id} 代理下载）
        if (event instanceof ToolResultEndEvent tre
                && ("present_file".equals(tre.getToolCallName())
                    || "present_url".equals(tre.getToolCallName()))) {
            emitFileReadyViaEventBus(sessionId, replyId, tre.getToolCallId());
        }

        // AGENT_END
        if (event.getType() == AgentEventType.AGENT_END) {
            log.info("[chat] agent completed: sessionId={}", sessionId);
            endTurnAndCleanupBuckets(lease, sessionId, turnEnded, turnBucketKeys);
        }
    }

    /**
     * turn 收尾 + 本 turn 桶清理（A4）：只有抢到收尾权（{@link TurnFinalizer#endTurn}
     * 3 参的 CAS 首胜返回 true）才清桶，且只清本 turn 在 {@code turnBucketKeys}
     * 里登记过的 key——并行 turn（其他 session）的桶条目不可达于本清理循环；
     * {@code remove} 天然幂等，与正常路径（End/合成时）的 remove 双删无冲突。
     *
     * <p>清桶发生在终态动作（closeSession/abandonSession）之后：桶纯内存、不产生
     * 任何 SSE 字节，先后不影响行为。
     *
     * <p>覆盖的终态：AGENT_END、源流 error/complete 回调、onCancel 看护强收（均走本方法）。
     * <b>HITL 是部分覆盖</b>：permission_ask 路径先 release 租约再直接 closeSession、不经
     * 3 参 endTurn，本 turn 的桶清理推迟到源 flux complete/error 回调的 endTurn——正常必达；
     * flux 永不 complete 且连接无 onCancel 时与现状一样不清（无恶化）。终态后理论上仍可能
     * 有极晚事件回调 re-put（与现状相同的既有边界，桶上限 64KB/2MB 兜底）。
     */
    private void endTurnAndCleanupBuckets(TurnLeaseGuard lease, String sessionId,
                                          java.util.concurrent.atomic.AtomicBoolean turnEnded,
                                          java.util.Set<String> turnBucketKeys) {
        if (!TurnFinalizer.endTurn(eventBus, lease, sessionId, turnEnded)) {
            return;
        }
        for (String toolCallId : turnBucketKeys) {
            toolCallNames.remove(toolCallId);
            presentFileBuffers.remove(toolCallId);
            writeFileInputBuffers.remove(toolCallId);
        }
    }

    // ===== 产出文件工具（present_file/present_url）累积 & file_ready 合成 =====

    /**
     * 该 toolCallId 是否属于指定工具：优先查登记表（ToolCallStart 登记的权威名字），
     * 表未命中时回落到事件自带的工具名——覆盖 ToolResult* 等本就携带真实名的事件类型。
     */
    private boolean isTool(String toolCallId, String expected, String eventToolName) {
        if (toolCallId == null) {
            return false;
        }
        String registered = toolCallNames.get(toolCallId);
        return expected.equals(registered != null ? registered : eventToolName);
    }

    private boolean isTool(String toolCallId, String expected) {
        return isTool(toolCallId, expected, null);
    }

    // ===== 私有可测性观察点（A4）：仅供测试断言，不进公有 API =====

    /** 三个工具桶条目数之和（toolCallNames/presentFileBuffers/writeFileInputBuffers） */
    int bucketEntryCount() {
        return toolCallNames.size() + presentFileBuffers.size() + writeFileInputBuffers.size();
    }

    /** 定向查看登记表中某 toolCallId 的工具名（测试断言「并行 turn 防误删」用） */
    String registeredToolName(String toolCallId) {
        return toolCallNames.get(toolCallId);
    }

    private void accumulatePresentFile(String toolCallId, String delta) {
        if (toolCallId == null) return;
        var buf = presentFileBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
        synchronized (buf) {
            int room = PRESENT_FILE_BUFFER_MAX - buf.length();
            if (room <= 0) {
                log.warn("present_file result buffer overflow for toolCallId {}, dropping tail", toolCallId);
                return;
            }
            // 溢出保留头部、截去尾部（原实现单个 delta 超限会整段丢弃，桶里空无一字）：
            // file_id/file_name 等元数据固定在 JSON 前部，头部留存即可被 parseFileResult 正则救回
            if (delta.length() > room) {
                log.warn("present_file result buffer overflow for toolCallId {}, dropping tail", toolCallId);
                delta = delta.substring(0, room);
            }
            buf.append(delta);
        }
    }

    private void emitFileReadyViaEventBus(String sessionId, String replyId, String toolCallId) {
        var buf = presentFileBuffers.remove(toolCallId);
        if (buf == null) return;
        String json;
        synchronized (buf) { json = buf.toString(); }
        try {
            var node = parseFileResult(json);
            if (node != null && node.has("error")) {
                log.debug("file tool returned error: {}, skip file_ready", node.get("error").asText());
                return;
            }
            if (node == null || !node.has("file_id")) {
                log.warn("file tool result missing file_id, skip file_ready (sid={})", sessionId);
                return;
            }
            var fileId = node.get("file_id").asText();
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "file_ready");
            payload.put("file_id", fileId);
            payload.put("file_name", node.get("file_name").asText());
            payload.put("mime_type", node.has("mime_type") ? node.get("mime_type").asText() : null);
            payload.put("size", node.has("size") ? node.get("size").asLong() : 0);
            payload.put("download_url", "/files/" + fileId);
            eventBus.emitSynthetic(sessionId, replyId, "file_ready",
                AgentEventSseSerializer.payload(payload));
            // 回写 reply_id 和 session_id 到 file_asset，供历史回放按消息/会话维度分发卡片
            fileAssetStore.updateReplyId(fileId, replyId);
            fileAssetStore.updateSessionId(fileId, sessionId);
            log.info("file_ready emitted for {} (sid={})", node.get("file_name").asText(), sessionId);
        } catch (Exception e) {
            log.warn("file_ready synthesis failed: {}", e.getMessage());
        }
    }

    /**
     * 产出文件工具结果文本 → JSON 节点。
     *
     * <p>全文解析失败时正则兜底：结果体可能超出 {@link #PRESENT_FILE_BUFFER_MAX} 被截尾
     * （大结果卸载/流式分片场景），而 file_id/file_name/mime_type/size 固定位于 JSON 前部，
     * 从残存头部提取即可救回下载卡片。
     */
    private com.fasterxml.jackson.databind.JsonNode parseFileResult(String json) {
        try {
            var node = JSON.readTree(json);
            if (node != null && node.isTextual()) {
                node = JSON.readTree(node.asText());
            }
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (Exception ignored) {
            // 落到正则兜底
        }
        var node = JSON.createObjectNode();
        var id = java.util.regex.Pattern
            .compile("\"file_id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").matcher(json);
        if (!id.find()) {
            return null;
        }
        node.put("file_id", id.group(1));
        var name = java.util.regex.Pattern
            .compile("\"file_name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        node.put("file_name", name.find() ? name.group(1) : "file");
        var mime = java.util.regex.Pattern
            .compile("\"mime_type\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (mime.find()) {
            node.put("mime_type", mime.group(1));
        }
        var size = java.util.regex.Pattern.compile("\"size\"\\s*:\\s*(\\d+)").matcher(json);
        node.put("size", size.find() ? Long.parseLong(size.group(1)) : 0L);
        return node;
    }

    // ===== write_file 输入截获 → KV =====

    private void accumulateWriteFileInput(String toolCallId, String delta) {
        if (toolCallId == null) return;
        var buf = writeFileInputBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
        synchronized (buf) {
            if (buf.length() + delta.length() > 2 * 1024 * 1024) {
                log.warn("write_file input buffer overflow for toolCallId {}, dropping tail", toolCallId);
                return;
            }
            buf.append(delta);
        }
    }

    private void syncWriteFileToKv(String toolCallId, String userKey) {
        var buf = writeFileInputBuffers.remove(toolCallId);
        if (buf == null || userKey == null || userKey.isBlank()) return;
        String json;
        synchronized (buf) { json = buf.toString(); }
        try {
            var node = JSON.readTree(json);
            if (node == null) return;
            var pathNode = node.has("path") ? node.get("path")
                : node.has("file_path") ? node.get("file_path") : null;
            var contentNode = node.has("content") ? node.get("content") : null;
            if (pathNode == null || contentNode == null) {
                log.debug("write_file input missing path/content, skip KV sync (toolCallId={})", toolCallId);
                return;
            }
            var relPath = pathNode.asText();
            var content = contentNode.asText();
            relPath = relPath.replace('\\', '/');
            if (relPath.startsWith("/workspace/")) relPath = relPath.substring("/workspace/".length());
            else if (relPath.startsWith("/")) relPath = relPath.substring(1);
            if (relPath.contains("..")) {
                log.debug("write_file path contains .., skip KV sync: {}", relPath);
                return;
            }
            long maxKvBytes = props.file().presentMaxMb() * 1024L * 1024L;
            if (content.length() > maxKvBytes) {
                log.debug("write_file content exceeds {}MB limit, skip KV sync: {}", props.file().presentMaxMb(), relPath);
                return;
            }
            boolean ok = workspaceReader.writeWorkspaceFile(userKey, relPath, content);
            if (ok) {
                // 落库登记 (相对路径 → userKey)：present_file 读 KV 需要写入侧命名空间键，
                // 而工具侧 ctx.userId 在 Channel 链路下是网关 peer（D3）。落库以支持跨副本。
                fileAssetStore.upsertKvSyncKey(relPath, userKey);
                log.info("write_file → KV sync: {} ({} chars) for user {}", relPath, content.length(), userKey);
            }
        } catch (Exception e) {
            log.debug("write_file KV sync parse failed (toolCallId={}): {}", toolCallId, e.getMessage());
        }
    }

    // ===== 审计 =====

    private void audit(AgentEvent event, String sessionId) {
        if (event instanceof ToolCallStartEvent tc) {
            recordAudit(sessionId, tc.getToolCallName(), tc.getToolCallId(), "TOOL_CALL_START", event);
        } else if (event instanceof ToolCallEndEvent tc) {
            recordAudit(sessionId, tc.getToolCallName(), tc.getToolCallId(), "TOOL_CALL_END", event);
        } else if (event instanceof ToolResultStartEvent tc) {
            recordAudit(sessionId, tc.getToolCallName(), tc.getToolCallId(), "TOOL_RESULT_START", event);
        } else if (event instanceof ToolResultEndEvent tc) {
            recordAudit(sessionId, tc.getToolCallName(), tc.getToolCallId(), "TOOL_RESULT_END", event);
        }
    }

    private void recordAudit(String sessionId, String toolName, String toolCallId, String state, AgentEvent event) {
        try {
            toolAuditStore.record(sessionId, toolName, toolCallId, state,
                AgentEventSseSerializer.payload(event));
        } catch (Exception e) {
            log.debug("audit record skipped (sid={}): {}", sessionId, e.getMessage());
        }
    }

    // ===== SSE 工厂方法 =====

    private static ServerSentEvent<String> waitingSSE() {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"waiting\"}")
            .build();
    }

    private static ServerSentEvent<String> errorSSE(String msg) {
        String payload = "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(msg) + "}";
        return ServerSentEvent.<String>builder().data(payload).build();
    }

    private static boolean isTrailingSandboxTeardownError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("No active sandbox")) return true;
        }
        return false;
    }

    /** POST 请求体：message 或 fileIds 至少一项；userId、sessionId 可选 */
    public record ChatRequest(String message, String userId, String sessionId, List<String> fileIds) {
    }
}
