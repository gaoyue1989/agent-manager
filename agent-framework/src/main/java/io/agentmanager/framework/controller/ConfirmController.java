package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.TurnLeaseGuard;
import io.agentmanager.framework.service.TurnLeaseStore;
import reactor.core.publisher.Flux;

/**
 * HITL 确认端点（业务能力，独立于 Debug 页面，见 docs/hitl-permission-plan.md 6.3）。
 *
 * <ul>
 *   <li>POST /threads/{sessionId}/confirm —— 携带确认决策恢复 agent，同步返回恢复执行后的最终回复</li>
 *   <li>POST /threads/{sessionId}/confirm-stream —— 恢复执行的事件流式下发（DURABLE_SSE：事件经 EventBus
 *       持久化 + 广播，SSE 断连后可通过 GET /subscribe 重连续传）</li>
 * </ul>
 *
 * <p>错误码（12.5）：缓存 miss → 404 {@code confirm_context_not_found}；重复确认（CAS 防护）→ 409
 * {@code confirm_already_consumed}；confirm-stream 预检失败以 error SSE 帧返回。
 *
 * <p>执行权语义：confirm 恢复 = 新执行段，confirm-stream 需先 acquire turn 租约；permission_ask
 * 暂停点锁已让出，此处通常可直接抢到；抢不到（并发确认/新消息正在执行）→ error 帧 turn_in_progress。
 */
@RestController
@RequestMapping("/threads/{sessionId}")
public class ConfirmController {

    private static final Logger log = LoggerFactory.getLogger(ConfirmController.class);

    private final AgentRuntimeService runtimeService;
    private final TurnLeaseStore turnLeaseStore;
    private final SessionEventBus eventBus;
    private final SessionUserStore sessionUserStore;
    private final McpToolRegistrar mcpToolRegistrar;

    public ConfirmController(AgentRuntimeService runtimeService,
                             TurnLeaseStore turnLeaseStore,
                             SessionEventBus eventBus,
                             SessionUserStore sessionUserStore,
                             McpToolRegistrar mcpToolRegistrar) {
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
        this.eventBus = eventBus;
        this.sessionUserStore = sessionUserStore;
        this.mcpToolRegistrar = mcpToolRegistrar;
    }

    /**
     * TOOL_CALL_START 命中 MCP App ui 映射时，序列化携带 ui 元数据（同 ChatStreamController）。
     * 非 ui 工具返回 null，payload 保持原词表。
     */
    private String payloadForEvent(io.agentscope.core.event.AgentEvent event) {
        if (event instanceof io.agentscope.core.event.ToolCallStartEvent tc) {
            var uiRef = mcpToolRegistrar.resolveUiRef(tc.getToolCallName());
            if (uiRef != null) {
                return AgentEventSseSerializer.payload(event, uiRef.resourceUri(), uiRef.serverName());
            }
        }
        return null;
    }

    /**
     * 同步版：恢复 agent 执行，返回最终回复（无状态架构：无事件扇出，调用方直接消费结果）。
     *
     * <p><b>Turn 租约</b>：与 {@code confirm-stream} 一致先抢租约再恢复——多副本部署下
     * 同一会话的并发确认/对话必须互斥。此前本端点无租约保护（confirm-stream 有），
     * 并发确认会真的执行两次工具；租约在 DB（跨 Pod 生效）。
     *
     * <p>抢不到租约返回 409 {@code turn_in_progress}：语义为"该会话有执行段在进行中"，
     * 调用方可稍后重试（与流式版返回 error 帧的处置差异：HTTP 端点用状态码表达）。
     */
    @PostMapping(value = "/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String sessionId, @RequestBody ConfirmRequest body) {
        // ★ Windows 路径安全化：与 ChatStreamController 保持一致
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);

        // 恢复确认时刷新会话-用户映射（确认恢复可能是新的入口，确保映射存在）
        var userId = sessionUserStore.findUserIdBySession(sessionId);
        if (userId != null) {
            sessionUserStore.upsert(sessionId, userId);
        }

        // 抢 Turn 租约：与 confirm-stream / chat 同一把锁（turn_lease 表，跨副本互斥）。
        // 未抢到说明该会话正有执行段在跑（另一副本的对话/确认，或本会话上一段未释放），
        // 此时不得恢复执行——否则两个执行段会同时写同一份 AgentState。
        var token = turnLeaseStore.tryAcquire(sessionId);
        if (token == null) {
            log.info("[confirm] turn_in_progress, rejected (sid={})", sessionId);
            return ResponseEntity.status(409).body(Map.of(
                "error", "turn_in_progress",
                "message", "Session '" + sessionId + "' has an active turn"));
        }
        var lease = new TurnLeaseGuard(turnLeaseStore, sessionId, token);

        try {
            var result = runtimeService.resumeWithConfirm(sessionId, null, body.results());
            return ResponseEntity.ok(result);
        } catch (AgentRuntimeService.ConfirmContextNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "confirm_context_not_found",
                "message", "Session not found or confirm context expired"));
        } catch (AgentRuntimeService.ConfirmAlreadyConsumedException e) {
            return ResponseEntity.status(409).body(Map.of(
                "error", "confirm_already_consumed",
                "message", "This confirm has already been processed"));
        } finally {
            // 租约必须释放：resumeWithConfirm 是阻塞调用（agent.call().block()），
            // 本轮执行段到此结束；不释放会让该会话被锁至 TTL 过期（默认 60s）
            lease.close();
        }
    }

    /**
     * 流式版：确认后事件流（DURABLE_SSE 架构）。
     *
     * <p>与 ChatStreamController.chat 相同的模式：
     * <ol>
     *   <li>acquire turn 租约</li>
     *   <li>beginTurn — 播种 seq 计数器并确保 EventBus 有输出通道</li>
     *   <li>先订阅 EventBus → SSE（避免与 agent 执行的竞态）</li>
     *   <li>启动 agent 恢复执行 → 事件写入 EventBus</li>
     *   <li>onCancel 仅取消 SSE 订阅，不 dispose agent 管道</li>
     * </ol>
     */
    @PostMapping(value = "/confirm-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> confirmStream(
            @PathVariable String sessionId, @RequestBody ConfirmRequest body) {
        // ★ Windows 路径安全化：与 ChatStreamController 保持一致
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);
        String finalSessionId = sessionId;

        // 恢复确认时刷新会话-用户映射
        var confirmUserId = sessionUserStore.findUserIdBySession(finalSessionId);
        if (confirmUserId != null) {
            sessionUserStore.upsert(finalSessionId, confirmUserId);
        }

        return Flux.<ServerSentEvent<String>>create(sink -> {
            // ===== 1. 预检查确认可用性 =====
            try {
                runtimeService.checkConfirmAvailable(finalSessionId);
            } catch (Exception e) {
                sink.next(errorSSE(e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName()));
                sink.complete();
                return;
            }

            // ===== 2. 抢 Turn 租约 =====
            var token = turnLeaseStore.tryAcquire(finalSessionId);
            if (token == null) {
                sink.next(errorSSE("turn_in_progress: session '" + finalSessionId
                    + "' has an active turn"));
                sink.complete();
                return;
            }
            TurnLeaseGuard lease = new TurnLeaseGuard(turnLeaseStore, finalSessionId, token);

            // ===== 3. 准备 EventBus Sinks =====
            String replyId = UUID.randomUUID().toString();
            // 本 turn 的收尾只做一次（同 ChatStreamController）：AGENT_END 处理与源 flux 的
            // complete/error 回调可能各自触发一次，晚到的那次不得重复 closeSession/release
            var turnEnded = new java.util.concurrent.atomic.AtomicBoolean(false);

            // 恢复流的**构建**是同步的，且会因用户输入抛异常（未知 tool_call_id →
            // IllegalArgumentException；上下文已被并发消费 → ConfirmContextNotFound）。
            // 此时租约已到手，不回滚就会被 TurnLeaseGuard 的续租线程永久持有
            // （token 匹配即持续续期）→ 该 session 再也无法执行，观察者也永不终止。
            Flux<io.agentscope.core.event.AgentEvent> resumeFlux;
            try {
                eventBus.beginTurn(finalSessionId);
                // 冷流：此处只做上下文消费与消息构建，尚未开始执行
                resumeFlux = runtimeService.resumeWithConfirmEvents(
                    finalSessionId, null, body.results());
            } catch (Exception e) {
                log.warn("confirm-stream setup failed, rolling back (sid={}): {}",
                    finalSessionId, e.getMessage());
                sink.next(errorSSE(e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName()));
                eventBus.closeSession(finalSessionId);   // 刷缓冲 + 释放 seq 计数器 + 关 sink
                lease.release();
                sink.complete();
                return;
            }

            // ===== 4. 先订阅 EventBus → SSE =====
            eventBus.subscribe(finalSessionId, 0, replyId)
                .subscribe(
                    sse -> sink.next(sse),
                    e -> {
                        log.warn("EventBus subscription error on confirm-stream (sid={}): {}",
                            finalSessionId, e.getMessage());
                        sink.error(e);
                    },
                    () -> sink.complete());

            // ===== 5. 启动 agent 恢复执行 → 事件写入 EventBus =====
            resumeFlux
                .subscribe(
                    event -> handleEventAndEmit(event, finalSessionId, replyId, lease, sink, turnEnded),
                    e -> {
                        log.warn("confirm-stream agent error (sid={}): {}",
                            finalSessionId, e.getMessage());
                        if (!lease.isLost()) {
                            // 丢锁后这场 error 多半是丢锁的后果，再落一条只会占用新 owner 的 seq
                            eventBus.emitSynthetic(finalSessionId, replyId, "error",
                                "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(
                                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "}");
                        }
                        endTurn(lease, finalSessionId, turnEnded);
                    },
                    // 正常完成：幂等兜底（AGENT_END 已在 handleEventAndEmit 中收尾）
                    () -> endTurn(lease, finalSessionId, turnEnded));

            // ===== 6. onCancel：仅取消 SSE 订阅，不 dispose agent 管道 =====
            sink.onCancel(() -> {
                log.info("SSE disconnected, agent execution continues on confirm-stream (sid={}, rid={})",
                    finalSessionId, replyId);
            });

        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // ===== 事件处理（写入 EventBus） =====

    /** 单帧处理：HITL 落库 + 事件写入 EventBus + 终态关闭 */
    private void handleEventAndEmit(io.agentscope.core.event.AgentEvent event,
                                    String sessionId, String replyId, TurnLeaseGuard lease,
                                    reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink,
                                    java.util.concurrent.atomic.AtomicBoolean turnEnded) {
        if (stopIfLeaseLost(lease, sessionId, sink)) {
            return;
        }

        // Channel 流程 HITL：permission_ask → 上下文落库 + 释放租约（执行段结束，锁让出）
        if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent) {
            runtimeService.storeConfirmContext(sessionId, event);
            lease.release();
            // HITL 暂停点：锁已让出、状态已持久化
        }

        // ★ 核心变化：事件写入 EventBus（而非直接写入 FluxSink）；
        // TOOL_CALL_START 命中 MCP App ui 映射时携带 ui 元数据（与 ChatStreamController 同一契约，
        // 恢复执行流里再调 UI 工具时卡片才能照常渲染）
        eventBus.emit(sessionId, event, replyId, payloadForEvent(event));

        // HITL 是 turn 边界：permission_ask 已广播，关闭 sink（同上）
        if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent) {
            eventBus.closeSession(sessionId);
        }

        // AGENT_END → 关闭 EventBus
        if (event.getType() == io.agentscope.core.event.AgentEventType.AGENT_END) {
            endTurn(lease, sessionId, turnEnded);
        }
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
     * 租约已失去：本副本不再拥有该 session 的写入权。继续 append 会与新 owner 的 seq
     * 区间重叠，所以立刻停手、丢弃缓冲、发终态帧。
     *
     * <p>终态帧只给本连接的客户端、**不落库**——此刻任何 append 都会占用可能与新 owner
     * 重叠的 seq（这也是不能用 emitSynthetic 的原因）。
     *
     * @return true = 本事件已被丢弃，调用方必须直接返回
     */
    private boolean stopIfLeaseLost(TurnLeaseGuard lease, String sessionId,
                                    reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink) {
        if (!lease.isLost()) {
            return false;
        }
        if (lease.tryMarkLostNotified()) {
            log.error("[confirm] turn lease lost, stopping writer (sid={})", sessionId);
            sink.next(interruptedSSE("lease_lost"));
            eventBus.abandonSession(sessionId);
            lease.release();
        }
        return true;
    }

    /**
     * turn 收尾：丢锁走 abandon（**丢弃**缓冲），正常走 closeSession（刷缓冲）。
     *
     * <p>顺序上先收尾再放锁：刷缓冲必须在仍持有租约时做完，否则另一个副本可能已经
     * 接管并按新的 MAX(seq) 播种、开始写，而我们这时才把按旧区间分配的缓冲行写下去
     * ——正是 C1 要防的重叠。
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

    private static ServerSentEvent<String> errorSSE(String msg) {
        return ServerSentEvent.<String>builder()
            .data(AgentEventSseSerializer.payload(Map.of("type", "error", "error", msg)))
            .build();
    }

    public record ConfirmRequest(List<Map<String, Object>> results) {
    }
}
