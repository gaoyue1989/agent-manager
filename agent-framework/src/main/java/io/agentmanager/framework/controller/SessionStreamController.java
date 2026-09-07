package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseGuard;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentmanager.framework.service.UiContextStore;
import io.agentmanager.framework.service.UploadWorkspaceInjector;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * 会话单次流端点（无状态单次流架构，/threads 会话业务 API）。
 *
 * <p>POST /threads/{sessionId}/chat —— SSE 单次流直吐：抢 Turn 租约（等待式，
 * 同 session 有活跃执行段 → 排队，SSE 发 waiting 帧）→ sendStream → 事件直吐 →
 * AGENT_END/error 帧关闭流、释放租约；permission_ask（HITL 暂停点）→ 上下文落库
 * confirm_context + 释放租约让出锁（执行权语义，见 stateless-single-stream-plan 4.1.3）。
 *
 * <p>日志审计（O3 定稿）：工具类事件异步批量落库（仅元信息，失败静默降级）。
 */
@RestController
@RequestMapping("/threads/{sessionId}")
public class SessionStreamController {

    private static final Logger log = LoggerFactory.getLogger(SessionStreamController.class);

    /** waiting 帧间隔：每 15s（防 Nginx 60s 读超时） */
    private static final Duration WAITING_FRAME_INTERVAL = Duration.ofSeconds(15);
    /** 租约排队等待超时：120s 后仍未拿到 → error 帧（turn_in_progress 兜底） */
    private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(120);

    private final ChatUiChannel chatChannel;
    private final AgentRuntimeService runtimeService;
    private final McpToolRegistrar mcpToolRegistrar;
    private final TurnLeaseStore turnLeaseStore;
    private final ToolAuditStore toolAuditStore;
    private final UploadWorkspaceInjector workspaceInjector;
    private final SandboxConfig sandboxConfig;
    /** present_file 工具结果文本累积（toolCallId → 文本桶，64KB 上限防内存膨胀） */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> presentFileBuffers =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final int PRESENT_FILE_BUFFER_MAX = 64 * 1024;

    public SessionStreamController(ChatUiChannel chatChannel,
                                   AgentRuntimeService runtimeService,
                                   McpToolRegistrar mcpToolRegistrar,
                                   TurnLeaseStore turnLeaseStore,
                                   ToolAuditStore toolAuditStore,
                                   UploadWorkspaceInjector workspaceInjector,
                                   SandboxConfig sandboxConfig) {
        this.chatChannel = chatChannel;
        this.runtimeService = runtimeService;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.turnLeaseStore = turnLeaseStore;
        this.toolAuditStore = toolAuditStore;
        this.workspaceInjector = workspaceInjector;
        this.sandboxConfig = sandboxConfig;
    }

    /** 无状态单次流：事件直吐，执行完即关闭（HTTP 200 + SSE）；排队等待时发 waiting 帧 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@PathVariable String sessionId,
                                              @RequestBody ChatRequest body) {
        var message = body.message();
        if ((message == null || message.isBlank()) && (body.fileIds() == null || body.fileIds().isEmpty())) {
            return Flux.just(errorSSE("message or fileIds is required"));
        }
        var userId = body.userId() != null ? body.userId() : "debug-user";

        return Flux.<ServerSentEvent<String>>create(sink -> {
            // ===== 抢 Turn 租约（等待式：超时发 error 帧兜底）=====
            var token = turnLeaseStore.tryAcquire(sessionId);
            long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT.toMillis();
            while (token == null && System.currentTimeMillis() < deadline) {
                // 等待期间发 waiting 帧（防 Nginx 60s 读超时；前端提示"排队等待中"）
                sink.next(waitingSSE());
                try {
                    Thread.sleep(WAITING_FRAME_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sink.complete();
                    return;
                }
                token = turnLeaseStore.tryAcquire(sessionId);
            }
            if (token == null) {
                // 排队超时兜底：对齐 409 turn_in_progress 语义（以 SSE error 帧表达）
                sink.next(errorSSE("turn_in_progress: session '" + sessionId
                    + "' has an active turn and queue timeout reached"));
                sink.complete();
                return;
            }

            // ===== 启动续租（绑定 turn 执行器生命周期，非 SSE 连接）=====
            TurnLeaseGuard lease = new TurnLeaseGuard(turnLeaseStore, sessionId, token);

            // ===== 消息列表：UI 交互上下文（4.7）经 UiContextInjectionHook 注入 =====
            // 上传文件注入（非沙箱：写本地工作区 {workspace}/{sessionId}/uploads/；
            // 沙箱：同时写本地工作区供 read_file 直读 + 沙箱 pending 注入由 OpenSandbox create/resume + middleware 处理）。
            // 隔离键用 sessionId——实测 SDK 文件系统根 = {workspace}/.agentscope/workspace/{sessionId}/
            // （ChatUiChannel peer 机制把隔离键置为会话 id，非请求 userId）。
            // 注入本身幂等（uniqueWorkspacePath 防覆盖），非沙箱直接写本地磁盘，沙箱模式下本地写入
            // 也可作为 read_file 的兜底（沙箱工具走 execd 不走 doExec，本地写入不冲突）。
            if (body.fileIds() != null) {
                for (var fileId : body.fileIds()) {
                    workspaceInjector.injectToWorkspace(fileId, sessionId);
                }
            }
            // 构造消息：图片 → ImageBlock 内联；文档/其他 → 路径提示（file-upload-download-plan §7.2）
            var blocks = workspaceInjector.buildContentBlocks(body.fileIds(), message, userId);
            var msg = Msg.builder().role(MsgRole.USER).name(userId)
                .metadata(Map.of(UiContextStore.METADATA_SESSION_KEY, sessionId))
                .content(blocks).build();
            var messages = new ArrayList<Msg>();
            messages.add(msg);

            // 会话经网关路由后其真实 key 为 (userId=peer, sessionId=gw-hash)——HITL 恢复
            // 必须复用该组合才能命中 pending 工具调用（storeConfirmContext 内部推导）
            // Disposable：客户端断开时主动 dispose，终止 agent 管道避免僵尸执行（资源浪费 + 状态污染）
            reactor.core.Disposable agentSubscription =
                chatChannel.sendStream(ChatUiRequest.withPeer(sessionId, messages))
                    .subscribe(
                        event -> handleEvent(sink, event, sessionId, lease),
                        e -> {
                            log.warn("session chat stream error (sid={}): {}", sessionId, e.getMessage());
                            if (isTrailingSandboxTeardownError(e)) {
                                // SDK 已知问题：沙箱收尾阶段（POST_CALL 后）偶发
                                // "No active sandbox"——此时所有业务事件（text delta / file_ready）
                                // 均已发出，仅属释放期异常：正常结束流，不发送 error 帧，
                                // 避免前端将已完成的对话判定为失败
                                log.info("ignore trailing sandbox teardown error (sid={})", sessionId);
                            } else {
                                sink.next(errorSSE(e));
                            }
                            lease.release();
                            sink.complete();
                        },
                        () -> {
                            // sendStream 自然完成：正常路径先收到 AGENT_END（置已释放），此处幂等兜底
                            // 释放（防御性：流异常结束但未走该事件时锁不会悬挂）
                            lease.release();
                            sink.complete();
                        });

            sink.onCancel(() -> {
                // 客户端断开（abort / Nginx 断流）：主动终止 agent 管道，防止僵尸执行
                // （资源浪费：sandbox 创建/工具调用继续运行；状态污染：后续 turn 收到脏事件）
                log.info("client disconnected, cancelling agent pipeline (sid={})", sessionId);
                agentSubscription.dispose();
                lease.release();
            });
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** 单帧处理：审计 + HITL 落库 + SSE 直吐 + 终态关闭/释放 */
    private void handleEvent(FluxSink<ServerSentEvent<String>> sink, AgentEvent event,
                             String sessionId, TurnLeaseGuard lease) {
        // 工具类事件 → 异步批量审计落库（仅元信息，失败静默）
        audit(event, sessionId);
        // present_file 工具结果累积（file-upload-download-plan §8.3）：
        // TextDelta 按 toolCallId 累积，End 时解析 JSON → 合成 file_ready 帧
        if (event instanceof io.agentscope.core.event.ToolResultTextDeltaEvent trd
                && "present_file".equals(trd.getToolCallName())) {
            accumulatePresentFile(trd.getToolCallId(), String.valueOf(trd.getDelta()));
        }
        // Channel 流程 HITL：permission_ask → 上下文落库 + 释放租约（执行段结束，锁让出）
        if (event instanceof RequireUserConfirmEvent) {
            runtimeService.storeConfirmContext(sessionId, event);
            lease.release();
            // HITL 暂停点：锁已让出、状态已持久化，流可关闭（前端已收到 permission_ask 帧
            // 渲染确认卡片；后续恢复走 confirm-stream，是新执行段需重新 acquire）
        }
        sink.next(toSSE(event, mcpToolRegistrar));
        // present_file 结果完成 → 解析累积 JSON 合成 file_ready 帧（插在 TOOL_RESULT_END 之后）
        if (event instanceof ToolResultEndEvent tre && "present_file".equals(tre.getToolCallName())) {
            emitFileReady(sink, tre.getToolCallId());
        }
        // AGENT_END → 关闭流、释放租约
        if (event.getType() == AgentEventType.AGENT_END) {
            lease.release();
            sink.complete();
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

    /** 解析累积 JSON → 合成 file_ready SSE 帧（JSON 畸形降级为无帧，不阻断流） */
    private void emitFileReady(FluxSink<ServerSentEvent<String>> sink, String toolCallId) {
        var buf = presentFileBuffers.remove(toolCallId);
        if (buf == null) {
            return;
        }
        String json;
        synchronized (buf) {
            json = buf.toString();
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            // SDK 对工具返回字符串再做一次 JSON 编码（TextDelta 携带 "{\"...\"}" 外层引号）：
            // Textual 节点需二次解析还原 JSON 对象
            if (node != null && node.isTextual()) {
                node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(node.asText());
            }
            if (node == null || !node.has("file_id") || !node.has("file_name")) {
                log.warn("present_file result missing file_id/file_name, skip file_ready");
                return;
            }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "file_ready");
            payload.put("file_id", node.get("file_id").asText());
            payload.put("file_name", node.get("file_name").asText());
            payload.put("mime_type", node.has("mime_type") ? node.get("mime_type").asText() : null);
            payload.put("size", node.has("size") ? node.get("size").asLong() : 0);
            // 下载 URL：前端 Next rewrite 前缀下相对路径（与 /agent/{name}/threads 同机制）
            payload.put("download_url", "/agent/release-agent/files/" + node.get("file_id").asText());
            sink.next(ServerSentEvent.<String>builder()
                .data(AgentEventSseSerializer.payload(payload))
                .build());
            log.info("file_ready emitted for {}", node.get("file_name").asText());
        } catch (Exception e) {
            log.warn("file_ready synthesis failed: {}", e.getMessage());
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

    private static ServerSentEvent<String> toSSE(AgentEvent event, McpToolRegistrar registrar) {
        String data;
        if (event instanceof ToolCallStartEvent tc) {
            // MCP Apps：工具带 ui 元数据时 payload 携带 ui 字段（裸名冲突时 resolveUiRef 返回 null 降级）
            var uiRef = registrar.resolveUiRef(tc.getToolCallName());
            data = uiRef != null
                ? AgentEventSseSerializer.payload(event, uiRef.resourceUri(), uiRef.serverName())
                : AgentEventSseSerializer.payload(event);
        } else {
            data = AgentEventSseSerializer.payload(event);
        }
        return ServerSentEvent.<String>builder()
            .data(data)
            .build();
    }

    private static ServerSentEvent<String> waitingSSE() {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"waiting\"}")
            .build();
    }

    private static ServerSentEvent<String> errorSSE(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return errorSSE(msg);
    }

    /**
     * 尾部沙箱收尾错误识别：SDK 在 agent 调用结束（POST_CALL）后访问已释放的
     * 沙箱文件系统，抛出 "No active sandbox"。此时业务事件已全部发出，
     * 该异常仅影响流的正常收尾，应忽略而非以 error 帧中断。
     */
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

    /** POST 请求体：message 或 fileIds 至少一项；userId 可选 */
    public record ChatRequest(String message, String userId, List<String> fileIds) {
    }
}