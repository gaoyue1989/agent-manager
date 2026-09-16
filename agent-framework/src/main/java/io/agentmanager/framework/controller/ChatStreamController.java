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

/**
 * 无路径变量的会话流端点 &mdash; sessionId 在请求体中，可选。
 *
 * <p>与 {@link SessionStreamController}（{@code POST /threads/{sessionId}/chat}）互补：
 * <ul>
 *   <li>{@code POST /threads/chat} &mdash; sessionId 在请求体中，可选</li>
 *   <li>不传 sessionId 时自动生成 UUID，首个 SSE 事件为 {@code session_created}</li>
 *   <li>传了 sessionId 则续接已有会话</li>
 * </ul>
 *
 * <p>前端推荐使用此端点，首次调用无需预知 sessionId：
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
 * <p>其余端点（subscribe / status / confirm / history）仍需 sessionId 在路径中，
 * 因为它们是面向已知会话的操作。
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

    /** present_file 工具结果文本累积（toolCallId &rarr; 文本桶，64KB 上限防内存膨胀） */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> presentFileBuffers =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** write_file 工具输入参数累积（toolCallId &rarr; JSON 片段桶），用于截获 path+content 后同步 KV */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> writeFileInputBuffers =
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
                                io.agentmanager.framework.service.FileAssetStore fileAssetStore) {
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
            TurnLeaseGuard lease = new TurnLeaseGuard(turnLeaseStore, finalSessionId, token);

            // ===== 3. 准备 EventBus Sinks =====
            String replyId = UUID.randomUUID().toString();
            eventBus.ensureSink(finalSessionId);

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
            var messages = new ArrayList<Msg>();
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
                        lease.release();
                        eventBus.closeSession(finalSessionId);
                    });

            // ===== 7. onCancel =====
            sink.onCancel(() -> {
                log.info("[chat] SSE disconnected, agent execution continues (sid={}, rid={})",
                    finalSessionId, replyId);
            });

        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // ===== 事件处理 =====

    private void handleEventAndEmit(AgentEvent event, String sessionId,
                                    String replyId, TurnLeaseGuard lease, String userId) {
        audit(event, sessionId);

        // write_file 输入参数截获
        if (!sandboxConfig.enabled()) {
            if (event instanceof ToolCallDeltaEvent delta
                    && "write_file".equals(delta.getToolCallName())) {
                accumulateWriteFileInput(delta.getToolCallId(), String.valueOf(delta.getDelta()));
            }
            if (event instanceof ToolCallEndEvent end && "write_file".equals(end.getToolCallName())) {
                syncWriteFileToKv(end.getToolCallId(), userId);
            }
        }

        // present_file 结果累积
        if (event instanceof ToolResultTextDeltaEvent trd
                && "present_file".equals(trd.getToolCallName())) {
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

        eventBus.emit(sessionId, event, replyId);

        // present_file 完成合成 file_ready
        if (event instanceof ToolResultEndEvent tre && "present_file".equals(tre.getToolCallName())) {
            emitFileReadyViaEventBus(sessionId, replyId, tre.getToolCallId());
        }

        // AGENT_END
        if (event.getType() == AgentEventType.AGENT_END) {
            log.info("[chat] agent completed: sessionId={}", sessionId);
            lease.release();
            eventBus.closeSession(sessionId);
        }
    }

    // ===== present_file 累积 & 合成 =====

    private void accumulatePresentFile(String toolCallId, String delta) {
        if (toolCallId == null) return;
        var buf = presentFileBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
        synchronized (buf) {
            if (buf.length() + delta.length() > PRESENT_FILE_BUFFER_MAX) {
                log.warn("present_file result buffer overflow for toolCallId {}, dropping tail", toolCallId);
                return;
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
            var node = JSON.readTree(json);
            if (node != null && node.isTextual()) node = JSON.readTree(node.asText());
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
            // 回写 reply_id 到 file_asset，供历史回放按消息维度分发卡片
            fileAssetStore.updateReplyId(fileId, replyId);
            log.info("file_ready emitted for {}", node.get("file_name").asText());
        } catch (Exception e) {
            log.warn("file_ready synthesis failed: {}", e.getMessage());
        }
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
