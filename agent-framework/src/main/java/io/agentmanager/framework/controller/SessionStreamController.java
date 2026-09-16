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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventBus.TurnStatus;
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
 * 会话单次流端点（durable-sse-plan 改造版）。
 *
 * <p>核心变化：解耦 SSE 连接与 agent 执行生命周期。
 * <ul>
 *   <li>agent 事件写入 SessionEventBus → 持久化 + 广播</li>
 *   <li>SSE 连接从 EventBus 订阅，断开不影响 agent 执行</li>
 *   <li>onCancel 不再 dispose agent 管道 / 释放租约</li>
 *   <li>全程心跳（EventBus 心跳流），防 Nginx/CDN 超时</li>
 * </ul>
 *
 * <p>新增端点：
 * <ul>
 *   <li>GET /threads/{sid}/subscribe — 重连续传（回放 + 实时）</li>
 *   <li>GET /threads/{sid}/status — 查询 turn 状态（刷新恢复用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/threads/{sessionId}")
public class SessionStreamController {

    private static final Logger log = LoggerFactory.getLogger(SessionStreamController.class);

    /** waiting 帧间隔：每 15s（防 Nginx 60s 读超时） */
    static Duration WAITING_FRAME_INTERVAL = Duration.ofSeconds(15);
    /** 租约排队等待超时：120s 后仍未拿到 → error 帧（turn_in_progress 兜底） */
    static Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(120);

    private final ChatUiChannel chatChannel;
    private final AgentRuntimeService runtimeService;
    private final McpToolRegistrar mcpToolRegistrar;
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

    /** present_file 工具结果文本累积（toolCallId → 文本桶，64KB 上限防内存膨胀） */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> presentFileBuffers =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** write_file 工具输入参数累积（toolCallId → JSON 片段桶），用于截获 path+content 后同步 KV */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> writeFileInputBuffers =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final int PRESENT_FILE_BUFFER_MAX = 64 * 1024;

    /** 共享 JSON 解析器（线程安全，避免每次调用 new ObjectMapper()） */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public SessionStreamController(ChatUiChannel chatChannel,
                                   AgentRuntimeService runtimeService,
                                   McpToolRegistrar mcpToolRegistrar,
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
                                   io.agentmanager.framework.service.FileAssetStore fileAssetStore) {
        this.chatChannel = chatChannel;
        this.runtimeService = runtimeService;
        this.mcpToolRegistrar = mcpToolRegistrar;
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
    }

    // ===== POST /chat：发起对话（SSE 单次流） =====

    /** 无状态单次流：事件经 EventBus 广播 + 持久化，SSE 连接断开不影响 agent 执行 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@PathVariable String sessionId,
                                              @RequestBody ChatRequest body,
                                              @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        var message = body.message();
        if ((message == null || message.isBlank()) && (body.fileIds() == null || body.fileIds().isEmpty())) {
            return Flux.just(errorSSE("message or fileIds is required"));
        }
        // 网关 Header 优先 > 请求体 userId > 默认 debug-user
        var userId = (headerUserId != null && !headerUserId.isBlank())
            ? headerUserId
            : (body.userId() != null && !body.userId().isBlank()) ? body.userId() : "debug-user";
        // sessionId：请求体优先（允许覆盖路径变量），路径变量兜底
        if (body.sessionId() != null && !body.sessionId().isBlank()) {
            sessionId = body.sessionId();
        }
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        userId = io.agentmanager.framework.util.PathSafe.sanitize(userId);

        String finalSessionId = sessionId;
        String finalUserId = userId;

        log.info("[session-chat] start: sessionId={}, userId={}, messageLen={}, fileCount={}",
            finalSessionId, finalUserId,
            message != null ? message.length() : 0,
            body.fileIds() != null ? body.fileIds().size() : 0);

        // 记录会话-用户映射，供 GET /threads?userId=xxx 过滤
        sessionUserStore.upsert(finalSessionId, finalUserId);

        return Flux.<ServerSentEvent<String>>create(sink -> {
            // ===== 1. 抢 Turn 租约（等待式：超时发 error 帧兜底）=====
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
                log.warn("[session-chat] turn_in_progress: sessionId={}, waiting timeout", finalSessionId);
                sink.next(errorSSE("turn_in_progress: session '" + finalSessionId
                    + "' has an active turn and queue timeout reached"));
                sink.complete();
                return;
            }

            // ===== 2. 启动续租（绑定 turn 执行器生命周期，非 SSE 连接）=====
            TurnLeaseGuard lease = new TurnLeaseGuard(turnLeaseStore, finalSessionId, token);

            // ===== 3. 准备 EventBus Sinks（确保 agent 事件有输出通道）=====
            String replyId = UUID.randomUUID().toString();
            eventBus.ensureSink(finalSessionId);

            // ===== 4. 构造消息 =====
            // 注入 @Skill 引用
            var processedMessage = skillInjectionService.injectSkillReferences(message);

            // 非沙箱模式：将上传文件注入本地磁盘工作区；沙箱模式由 OpenSandbox.injectPendingUploads() 经 execd files API 注入容器
            if (body.fileIds() != null && !(sandboxConfig != null && sandboxConfig.enabled())) {
                for (var fileId : body.fileIds()) {
                    workspaceInjector.injectToWorkspace(fileId, finalSessionId);
                }
            }
            var blocks = workspaceInjector.buildContentBlocks(body.fileIds(), processedMessage, finalUserId);
            var msg = Msg.builder().role(MsgRole.USER).name(finalUserId)
                .metadata(Map.of(UiContextStore.METADATA_SESSION_KEY, finalSessionId))
                .content(blocks).build();
            var messages = new ArrayList<Msg>();
            messages.add(msg);

            // ===== 5. 先订阅 EventBus（避免与 agent 执行的竞态——首批事件可能丢失） =====
            eventBus.subscribe(finalSessionId, 0, replyId)
                .subscribe(
                    sse -> sink.next(sse),
                    e -> {
                        log.warn("EventBus subscription error (sid={}): {}", finalSessionId, e.getMessage());
                        sink.error(e);
                    },
                    () -> sink.complete());

            // ===== 6. 启动 agent 执行 → 事件写入 EventBus =====
            chatChannel.sendStream(ChatUiRequest.withPeer(finalSessionId, messages))
                .subscribe(
                    event -> handleEventAndEmit(event, finalSessionId, replyId, lease, finalUserId),
                    e -> {
                        log.warn("session chat stream error (sid={}): {}", finalSessionId, e.getMessage());
                        if (isTrailingSandboxTeardownError(e)) {
                            log.info("ignore trailing sandbox teardown error (sid={})", finalSessionId);
                        } else {
                            eventBus.emitSynthetic(finalSessionId, replyId, "error",
                                "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(
                                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "}");
                        }
                        lease.release();
                        eventBus.closeSession(finalSessionId);
                    },
                    () -> {
                        // sendStream 自然完成：正常路径先收到 AGENT_END（置已释放），此处幂等兜底
                        lease.release();
                        eventBus.closeSession(finalSessionId);
                    });

            // ===== 7. onCancel：仅取消 SSE 订阅，不 dispose agent 管道 =====
            sink.onCancel(() -> {
                log.info("[session-chat] SSE disconnected, agent execution continues (sid={}, rid={})",
                    finalSessionId, replyId);
                // 不调用 agentSubscription.dispose()
                // 不调用 lease.release() —— turn 仍在执行
                // EventBus Sinks 由 agent 执行结束时 closeSession 清理
            });

        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // ===== GET /subscribe：重连续传 =====

    /**
     * 订阅 session 的实时事件流（含回放）。
     *
     * <p>前端刷新后调用此端点续传：
     * <ul>
     *   <li>{@code afterSeq}：回放游标，从 lastEventId 之后开始回放</li>
     *   <li>{@code replyId}：turn 标识，仅回放/订阅指定 turn 的事件</li>
     * </ul>
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribe(
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer afterSeq,
            @RequestParam(required = false) String replyId) {

        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        String finalSessionId = sessionId;

        // 如果 turn 已完成，回放历史 + done 帧后关闭
        var status = eventBus.turnStatus(finalSessionId);
        if (status == TurnStatus.COMPLETED) {
            return replayAndClose(finalSessionId, replyId, afterSeq);
        }

        // turn 进行中或 idle（可能有历史事件需回放）：回放 + 实时订阅
        return eventBus.subscribe(finalSessionId,
            afterSeq != null ? afterSeq : 0,
            replyId);
    }

    /** 回放后追加 done 帧并关闭（仅从 eventStore 读取，不订阅实时流） */
    private Flux<ServerSentEvent<String>> replayAndClose(String sessionId, String replyId, Integer afterSeq) {
        return eventBus.replayOnly(sessionId, replyId, afterSeq != null ? afterSeq : 0)
            .concatWith(Flux.just(ServerSentEvent.<String>builder()
                .data("{\"type\":\"done\"}")
                .build()));
    }

    // ===== GET /status：查询 turn 状态 =====

    /**
     * 查询 session 当前 turn 状态（前端刷新恢复用）。
     *
     * <p>响应示例：
     * <pre>{@code
     * {
     *   "session_id": "...",
     *   "state": "working",          // working / completed / waiting_confirm / idle
     *   "latest_event_seq": 42,
     *   "reply_id": "...",
     *   "pending_confirm": null      // 或 HITL 确认上下文
     * }
     * }</pre>
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status(@PathVariable String sessionId) {
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        var leaseHeld = turnLeaseStore.isHeld(sessionId);
        var busStatus = eventBus.turnStatus(sessionId);
        var latestEvent = eventStore.findLatest(sessionId);
        var pendingConfirm = runtimeService.findPendingConfirm(sessionId);

        String state;
        if (pendingConfirm != null) {
            state = "waiting_confirm";
        } else if (leaseHeld || busStatus == TurnStatus.WORKING) {
            state = "working";
        } else if (latestEvent != null && "AGENT_END".equals(latestEvent.type())) {
            state = "completed";
        } else {
            state = "idle";
        }

        var maxSeq = eventStore.findMaxSeq(sessionId);

        return Map.of(
            "session_id", sessionId,
            "state", state,
            "latest_event_seq", maxSeq,
            "reply_id", latestEvent != null && latestEvent.replyId() != null
                ? latestEvent.replyId() : eventBus.currentReplyId(sessionId) != null
                ? eventBus.currentReplyId(sessionId) : "",
            "pending_confirm", pendingConfirm != null ? pendingConfirm : ""
        );
    }

    // ===== 事件处理（写入 EventBus 而非 FluxSink） =====

    /** 单帧处理：审计 + HITL 落库 + 事件写入 EventBus + 终态关闭 */
    private void handleEventAndEmit(AgentEvent event, String sessionId,
                                    String replyId, TurnLeaseGuard lease, String userId) {
        // 工具类事件 → 异步批量审计落库（仅元信息，失败静默）
        audit(event, sessionId);

        // ===== write_file 输入参数截获（非沙箱模式下同步 KV） =====
        if (!sandboxConfig.enabled()) {
            // 累积 write_file 的 ToolCallDelta（JSON 输入片段）
            if (event instanceof ToolCallDeltaEvent delta
                    && "write_file".equals(delta.getToolCallName())) {
                accumulateWriteFileInput(delta.getToolCallId(), String.valueOf(delta.getDelta()));
            }
            // write_file 工具调用完成 → 解析 path+content，写入 KV
            if (event instanceof ToolCallEndEvent end && "write_file".equals(end.getToolCallName())) {
                syncWriteFileToKv(end.getToolCallId(), userId);
            }
        }

        // present_file 工具结果累积
        if (event instanceof ToolResultTextDeltaEvent trd
                && "present_file".equals(trd.getToolCallName())) {
            accumulatePresentFile(trd.getToolCallId(), String.valueOf(trd.getDelta()));
        }

        // Channel 流程 HITL：permission_ask → 上下文落库 + 释放租约（执行段结束，锁让出）
        if (event instanceof RequireUserConfirmEvent) {
            log.info("[session-chat] HITL permission_ask: sessionId={}, tools={}", sessionId,
                ((RequireUserConfirmEvent) event).getToolCalls().stream()
                    .map(tc -> tc.getName()).toList());
            runtimeService.storeConfirmContext(sessionId, event);
            lease.release();
            // HITL 暂停点：锁已让出、状态已持久化
            // EventBus 在 permission_ask 事件发射后由 AGENT_END 或 error 时关闭
        }

        // ★ 核心变化：事件写入 EventBus（而非直接写入 FluxSink）
        eventBus.emit(sessionId, event, replyId);

        // present_file 结果完成 → 解析累积 JSON 合成 file_ready 帧（通过 EventBus 发射合成事件）
        if (event instanceof ToolResultEndEvent tre && "present_file".equals(tre.getToolCallName())) {
            emitFileReadyViaEventBus(sessionId, replyId, tre.getToolCallId());
        }

        // AGENT_END → 释放租约 + 关闭 EventBus
        if (event.getType() == AgentEventType.AGENT_END) {
            log.info("[session-chat] agent completed: sessionId={}", sessionId);
            lease.release();
            eventBus.closeSession(sessionId);
        }
    }

    /** present_file 结果文本累积（64KB 单桶上限防内存膨胀，超出丢弃并告警） */
    private void accumulatePresentFile(String toolCallId, String delta) {
        if (toolCallId == null) {
            return;
        }
        var buf = presentFileBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
        synchronized (buf) {
            if (buf.length() + delta.length() > PRESENT_FILE_BUFFER_MAX) {
                log.warn("present_file result buffer overflow for toolCallId {}, dropping tail", toolCallId);
                return;
            }
            buf.append(delta);
        }
    }

    /** 解析累积 JSON → 通过 EventBus 合成 file_ready 事件 */
    private void emitFileReadyViaEventBus(String sessionId, String replyId, String toolCallId) {
        var buf = presentFileBuffers.remove(toolCallId);
        if (buf == null) {
            return;
        }
        String json;
        synchronized (buf) {
            json = buf.toString();
        }
        try {
            var node = JSON.readTree(json);
            if (node != null && node.isTextual()) {
                node = JSON.readTree(node.asText());
            }
            // error 结果：工具明确返回了错误，不是畸形数据，降级为 debug
            if (node != null && node.has("error")) {
                log.debug("present_file returned error: {}, skip file_ready", node.get("error").asText());
                return;
            }
            if (node == null || !node.has("file_id") || !node.has("file_name")) {
                log.warn("present_file result missing file_id/file_name, skip file_ready");
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
            // 回写 reply_id 和 session_id 到 file_asset，供历史回放按消息维度分发卡片
            fileAssetStore.updateReplyId(fileId, replyId);
            fileAssetStore.updateSessionId(fileId, sessionId);
            log.info("file_ready emitted for {} (sid={})", node.get("file_name").asText(), sessionId);
        } catch (Exception e) {
            log.warn("file_ready synthesis failed: {}", e.getMessage());
        }
    }

    // ===== write_file 输入截获 → KV 同步 =====

    /** write_file ToolCallDelta 输入参数累积 */
    private void accumulateWriteFileInput(String toolCallId, String delta) {
        if (toolCallId == null) {
            return;
        }
        var buf = writeFileInputBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
        synchronized (buf) {
            // write_file 输入可能很大（大文件），设 2MB 上限
            if (buf.length() + delta.length() > 2 * 1024 * 1024) {
                log.warn("write_file input buffer overflow for toolCallId {}, dropping tail", toolCallId);
                return;
            }
            buf.append(delta);
        }
    }

    /** write_file 工具调用结束 → 解析累积的 path + content，同步写入 KV */
    private void syncWriteFileToKv(String toolCallId, String userKey) {
        var buf = writeFileInputBuffers.remove(toolCallId);
        if (buf == null || userKey == null || userKey.isBlank()) {
            return;
        }
        String json;
        synchronized (buf) {
            json = buf.toString();
        }
        try {
            var node = JSON.readTree(json);
            if (node == null) {
                return;
            }
            // 提取 path 和 content 字段（兼容 snake_case 与 camelCase）
            var pathNode = node.has("path") ? node.get("path")
                : node.has("file_path") ? node.get("file_path") : null;
            var contentNode = node.has("content") ? node.get("content") : null;
            if (pathNode == null || contentNode == null) {
                log.debug("write_file input missing path/content, skip KV sync (toolCallId={})", toolCallId);
                return;
            }
            var relPath = pathNode.asText();
            var content = contentNode.asText();
            // 路径规范化：去掉 /workspace/ 前缀、前导 /，与 RemoteFilesystem 语义对齐
            relPath = relPath.replace('\\', '/');
            if (relPath.startsWith("/workspace/")) {
                relPath = relPath.substring("/workspace/".length());
            } else if (relPath.startsWith("/")) {
                relPath = relPath.substring(1);
            }
            if (relPath.contains("..")) {
                log.debug("write_file path contains .., skip KV sync: {}", relPath);
                return;
            }
            // KV 内容大小限制：与 present_file 一致
            long maxKvBytes = props.file().presentMaxMb() * 1024L * 1024L;
            if (content.length() > maxKvBytes) {
                log.debug("write_file content exceeds {}MB limit, skip KV sync: {}", props.file().presentMaxMb(), relPath);
                return;
            }
            boolean ok = workspaceReader.writeWorkspaceFile(userKey, relPath, content);
            if (ok) {
                log.info("write_file → KV sync: {} ({} chars) for user {}", relPath, content.length(), userKey);
            }
        } catch (Exception e) {
            log.debug("write_file KV sync parse failed (toolCallId={}): {}", toolCallId, e.getMessage());
        }
    }

    /** 工具类事件审计（异步批量，仅元信息——何时/何工具/何状态，见 4.1.2） */
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

    // ===== 静态工具方法 =====

    private static ServerSentEvent<String> waitingSSE() {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"waiting\"}")
            .build();
    }

    private static ServerSentEvent<String> errorSSE(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return errorSSE(msg);
    }

    private static boolean isTrailingSandboxTeardownError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("No active sandbox")) {
                return true;
            }
        }
        return false;
    }

    private static ServerSentEvent<String> errorSSE(String msg) {
        String payload = "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(msg) + "}";
        return ServerSentEvent.<String>builder().data(payload).build();
    }

    /** POST 请求体：message 或 fileIds 至少一项；userId、sessionId 可选 */
    public record ChatRequest(String message, String userId, String sessionId, List<String> fileIds) {
    }
}
