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
            // 本 turn 的收尾只做一次。AGENT_END 处理与源 flux 的 complete/error 回调可能各自
            // 触发一次收尾，且 harness 的 flux 可能在 AGENT_END 事件之后**数秒**才 complete——
            // 迟到的那次若再走 closeSession，会把**下一个** turn 刚建好的 sink 拆掉
            // （实测：0.4s 内前后脚的两轮对话，第二轮的 permission_ask 被迟到关闭吞掉，
            // 前端收不到 HITL 确认卡；approval-forms e2e 2026-09-17 复现）。
            var turnEnded = new java.util.concurrent.atomic.AtomicBoolean(false);

            // 准备阶段的异常必须回滚已获取的 turn_lease。TurnLeaseGuard 的后台续租线程
            // 不看本段是否还活着——只要 token 仍匹配就持续续期，因此漏放租约意味着该
            // session 被**永久**锁死：后续每个请求都拿不到租约，观察者也会一直 probe
            // 到 RUNNING。构造消息这一步会因用户输入抛异常（fileId 失效 → 工作区注入
            // 失败），所以这不是理论路径。
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
                        event -> handleEventAndEmit(event, finalSessionId, replyId, lease, finalUserId, sink, turnEnded),
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
                            endTurn(lease, finalSessionId, turnEnded);
                        },
                        () -> endTurn(lease, finalSessionId, turnEnded));
            } catch (Exception e) {
                log.warn("[chat] turn setup failed, rolling back (sid={}): {}",
                    finalSessionId, e.getMessage());
                sink.next(errorSSE("turn_setup_failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                eventBus.closeSession(finalSessionId);   // 刷缓冲 + 释放 seq 计数器 + 关 sink
                lease.release();
                sink.complete();
                return;
            }

            // ===== 7. onCancel =====
            sink.onCancel(() -> {
                log.info("[chat] SSE disconnected, agent execution continues (sid={}, rid={})",
                    finalSessionId, replyId);
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
                                    java.util.concurrent.atomic.AtomicBoolean turnEnded) {
        if (stopIfLeaseLost(lease, sessionId, sink)) {
            return;
        }

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

        eventBus.emit(sessionId, event, replyId, payloadForEvent(event));

        // HITL 是 turn 边界：permission_ask 已广播，关闭 sink 让订阅者正常结束。
        // 必须在 emit 之后——否则订阅者收不到 permission_ask（durable-sse-multinode-plan §3.4.4）。
        if (event instanceof RequireUserConfirmEvent) {
            eventBus.closeSession(sessionId);
        }

        // present_file 完成合成 file_ready
        if (event instanceof ToolResultEndEvent tre && "present_file".equals(tre.getToolCallName())) {
            emitFileReadyViaEventBus(sessionId, replyId, tre.getToolCallId());
        }

        // AGENT_END
        if (event.getType() == AgentEventType.AGENT_END) {
            log.info("[chat] agent completed: sessionId={}", sessionId);
            endTurn(lease, sessionId, turnEnded);
        }
    }

    /**
     * 租约已失去：本副本不再拥有该 session 的写入权。
     *
     * <p>继续 append 会与新 owner 的 seq 区间重叠——这正是 C1 要防的事——所以立刻停手、
     * 丢弃缓冲、把控制权交还客户端。终态帧**只发本连接的客户端、不落库**：此刻任何
     * append 都会占用可能与新 owner 重叠的 seq（这也是不能用 emitSynthetic 的原因）。
     *
     * @return true = 本事件已被丢弃，调用方必须直接返回
     */
    private boolean stopIfLeaseLost(TurnLeaseGuard lease, String sessionId,
                                    FluxSink<ServerSentEvent<String>> sink) {
        if (!lease.isLost()) {
            return false;
        }
        if (lease.tryMarkLostNotified()) {
            log.error("[chat] turn lease lost, stopping writer (sid={})", sessionId);
            sink.next(interruptedSSE("lease_lost"));
            eventBus.abandonSession(sessionId);
            lease.release();
        }
        return true;
    }

    /** 带「只收尾一次」保护的 turn 收尾：晚到的 complete/error 回调不得重复执行 */
    private void endTurn(TurnLeaseGuard lease, String sessionId,
                         java.util.concurrent.atomic.AtomicBoolean turnEnded) {
        if (!turnEnded.compareAndSet(false, true)) {
            return;
        }
        endTurn(lease, sessionId);
    }

    /**
     * turn 收尾：丢锁走 abandon（**丢弃**缓冲），正常走 closeSession（刷缓冲）。
     *
     * <p>顺序上先收尾再放锁：刷缓冲必须在仍持有租约时做完，否则另一个副本可能已经
     * 接管并按新的 MAX(seq) 播种、开始写，而我们这时才把按旧区间分配的缓冲行写下去
     * ——正是 C1 要防的重叠。代价是流结束与租约释放之间有一个极短窗口（客户端已看到
     * 结束、锁还在我们手上），抢锁方按 ACQUIRE_TIMEOUT 排队等一拍即可。
     *
     * <p>{@code isLost()} 必须在 {@code release()} **之前**求值：release 之后
     * {@code released} 参与判断，时间判据会被短路成 false，丢锁的 turn 就误走刷缓冲了。
     */
    private void endTurn(TurnLeaseGuard lease, String sessionId) {
        boolean lost = lease.isLost();
        if (lost) {
            eventBus.abandonSession(sessionId);
        } else {
            eventBus.closeSession(sessionId);
        }
        lease.release();
    }

    /** 租约丢失的终态帧：不落库、不占 seq，只给本连接的客户端 */
    private static ServerSentEvent<String> interruptedSSE(String reason) {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"interrupted\",\"reason\":\"" + reason + "\"}")
            .build();
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
            // 回写 reply_id 和 session_id 到 file_asset，供历史回放按消息/会话维度分发卡片
            fileAssetStore.updateReplyId(fileId, replyId);
            fileAssetStore.updateSessionId(fileId, sessionId);
            log.info("file_ready emitted for {} (sid={})", node.get("file_name").asText(), sessionId);
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
