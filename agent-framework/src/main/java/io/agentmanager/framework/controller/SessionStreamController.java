package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
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

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseGuard;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentmanager.framework.service.UiContextStore;
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

    public SessionStreamController(ChatUiChannel chatChannel,
                                   AgentRuntimeService runtimeService,
                                   McpToolRegistrar mcpToolRegistrar,
                                   TurnLeaseStore turnLeaseStore,
                                   ToolAuditStore toolAuditStore) {
        this.chatChannel = chatChannel;
        this.runtimeService = runtimeService;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.turnLeaseStore = turnLeaseStore;
        this.toolAuditStore = toolAuditStore;
    }

    /** 无状态单次流：事件直吐，执行完即关闭（HTTP 200 + SSE）；排队等待时发 waiting 帧 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@PathVariable String sessionId,
                                              @RequestBody ChatRequest body) {
        var message = body.message();
        if (message == null || message.isBlank()) {
            return Flux.just(errorSSE("message is required"));
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
            var messages = new ArrayList<Msg>();
            messages.add(Msg.builder().role(MsgRole.USER).name(userId)
                .metadata(Map.of(UiContextStore.METADATA_SESSION_KEY, sessionId))
                .textContent(message).build());

            // 会话经网关路由后其真实 key 为 (userId=peer, sessionId=gw-hash)——HITL 恢复
            // 必须复用该组合才能命中 pending 工具调用（storeConfirmContext 内部推导）
            chatChannel.sendStream(ChatUiRequest.withPeer(sessionId, messages))
                .subscribe(
                    event -> handleEvent(sink, event, sessionId, lease),
                    e -> {
                        log.warn("session chat stream error (sid={}): {}", sessionId, e.getMessage());
                        lease.release();
                        sink.next(errorSSE(e));
                        sink.complete();
                    },
                    () -> {
                        // sendStream 自然完成：正常路径先收到 AGENT_END（置已释放），此处幂等兜底
                        // 释放（防御性：流异常结束但未走该事件时锁不会悬挂）
                        lease.release();
                        sink.complete();
                    });

            sink.onCancel(() -> {
                // 客户端断开（abort / Nginx 断流）：不主动释放锁——若是 permission_ask 后
                // 的"观众离场"，锁已让出；若是取消活跃 turn，由 TTL 60s 自然过期兜底
                // （R12 待 SPIKE S2 定稿 cancel 语义）
                lease.release();
            });
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** 单帧处理：审计 + HITL 落库 + SSE 直吐 + 终态关闭/释放 */
    private void handleEvent(FluxSink<ServerSentEvent<String>> sink, AgentEvent event,
                             String sessionId, TurnLeaseGuard lease) {
        // 工具类事件 → 异步批量审计落库（仅元信息，失败静默）
        audit(event, sessionId);
        // Channel 流程 HITL：permission_ask → 上下文落库 + 释放租约（执行段结束，锁让出）
        if (event instanceof RequireUserConfirmEvent) {
            runtimeService.storeConfirmContext(sessionId, event);
            lease.release();
            // HITL 暂停点：锁已让出、状态已持久化，流可关闭（前端已收到 permission_ask 帧
            // 渲染确认卡片；后续恢复走 confirm-stream，是新执行段需重新 acquire）
        }
        sink.next(toSSE(event, mcpToolRegistrar));
        // AGENT_END → 关闭流、释放租约
        if (event.getType() == AgentEventType.AGENT_END) {
            lease.release();
            sink.complete();
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

    private static ServerSentEvent<String> errorSSE(String msg) {
        String payload = "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(msg) + "}";
        return ServerSentEvent.<String>builder().data(payload).build();
    }

    /** POST 请求体：message 必填，userId 可选 */
    public record ChatRequest(String message, String userId) {
    }
}