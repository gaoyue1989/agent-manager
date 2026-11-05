package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.service.AgentRuntimeService.ConfirmDeadlineExpiredException;
import io.agentmanager.framework.controller.TurnFinalizer;

/**
 * D16 确认硬截止的自动收尾（sandbox-pool-design D16 ①②）。
 *
 * <p>ASKING 挂起超 {@code AGENT_CONFIRM_DEADLINE_MINUTES} 后的三处触发点共用本服务：
 * <ol>
 *   <li>迟到确认（POST /confirm / confirm-stream 预检超截止）→ 410 confirm_expired，
 *       异步按拒绝收尾（fire-and-forget，不拖慢响应）；</li>
 *   <li>迟到普通消息（/threads/chat 入口预检超截止）→ <b>同步</b>收尾后放行新 turn
 *       （必须等 ASKING 解除，否则 SDK 在 turn 入口拒绝新消息）；</li>
 *   <li>永不交互 → 不收尾（state ASKING 无害挂起，零后台任务——D16 红线）。</li>
 * </ol>
 *
 * <p>收尾 = 以全 false 结果（DENY）走现有 confirm 恢复流（复用 reject→DENY 分支）：
 * 模型看到工具被拒、turn 正常收尾、state 从 ASKING 解锁——与用户手动点「拒绝」同构，
 * 非新事件类型（设计 §11 模型视角核对）。收尾 turn 的事件经 EventBus 正常广播，
 * 用户经 /subscribe 续传或刷新可见「该工具已按超时拒绝」的回复。
 *
 * <p>幂等与并发：进程内 in-flight 标记 + turn 租约跨副本互斥（第一个收尾解除挂起后，
 * 并发的第二个收尾因无挂起可恢复而失败放弃）。
 */
@Service
public class ConfirmDeadlineService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmDeadlineService.class);

    /** 收尾抢租约等待：覆盖 ask 段收尾尾部时延（与 ConfirmController.ACQUIRE_WAIT 同量级） */
    private static final Duration ACQUIRE_WAIT = Duration.ofSeconds(10);

    private final AgentRuntimeService runtimeService;
    private final TurnLeaseStore turnLeaseStore;
    private final SessionEventBus eventBus;
    private final AgentStateReader agentStateReader;
    private final ConfirmContextStore confirmContextStore;

    /** 进程内收尾去重（跨副本互斥靠 turn 租约；本表仅为挡住同副本重复触发） */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    /** 失败留痕（字段注入 required=false：单测直接 new 时为 null，跳过留痕） */
    @Autowired(required = false)
    private SessionMessageStore sessionMessageStore;

    public ConfirmDeadlineService(AgentRuntimeService runtimeService,
                                  TurnLeaseStore turnLeaseStore,
                                  SessionEventBus eventBus,
                                  AgentStateReader agentStateReader,
                                  ConfirmContextStore confirmContextStore) {
        this.runtimeService = runtimeService;
        this.turnLeaseStore = turnLeaseStore;
        this.eventBus = eventBus;
        this.agentStateReader = agentStateReader;
        this.confirmContextStore = confirmContextStore;
    }

    /**
     * 仅截止判定（不收尾）：挂起超 {@code AGENT_CONFIRM_DEADLINE_MINUTES} 即抛异常。
     * 供确认入口（POST /confirm / chat 入口预检）在正常流程前预检；无挂起/不可判定时静默放行。
     *
     * @throws ConfirmDeadlineExpiredException 挂起已超截止
     */
    public void checkDeadline(String sessionId) {
        runtimeService.checkConfirmDeadline(sessionId);
    }

    /**
     * 布尔版截止判定（不抛异常）：true = 挂起已超硬截止（调用方触发 {@link #finalizeByDeny}）。
     * chat 入口预检用——持有 turn 租约时必须先释放再收尾（finalizeByDeny 内部要抢同一租约），
     * 布尔判定让调用方先做租约交接。
     */
    public boolean isExpired(String sessionId) {
        try {
            runtimeService.checkConfirmDeadline(sessionId);
            return false;
        } catch (ConfirmDeadlineExpiredException e) {
            return true;
        }
    }

    /**
     * 挂起已超截止时自动按拒绝收尾。
     *
     * @param sessionId 会话 id（raw，内部补全 tenant 前缀）
     * @return true = 收尾完成（挂起已解除）；false = 无需收尾/收尾未发起（调用方按原语义继续）
     */
    public boolean finalizeIfExpired(String sessionId) {
        // 1. 截止判定（未超截止/截止语义关闭/不可判定 → 不收尾）
        try {
            runtimeService.checkConfirmDeadline(sessionId);
            return false;   // 未超截止：正常确认路径继续
        } catch (ConfirmDeadlineExpiredException e) {
            // 已超截止：进入收尾
        }
        return finalizeByDeny(sessionId);
    }

    /**
     * 无条件按拒绝收尾（调用方已自行判定超截止）。全 false 结果走现有恢复流。
     *
     * @return true = 收尾完成；false = 未发起（抢租约失败/无挂起项/已在收尾）
     */
    public boolean finalizeByDeny(String sessionId) {
        if (inFlight.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            log.info("[confirm-expired] finalize already in flight (sid={})", sessionId);
            return false;
        }
        try {
            return doFinalize(sessionId);
        } finally {
            inFlight.remove(sessionId);
        }
    }

    private boolean doFinalize(String sessionId) {
        var ids = pendingToolCallIds(sessionId);
        if (ids.isEmpty()) {
            log.info("[confirm-expired] no pending tools to deny (sid={})", sessionId);
            return false;
        }
        log.info("[confirm-expired] auto-deny expired confirm (sid={}, tools={})", sessionId, ids);

        // 抢租约（跨副本互斥）：抢不到说明另一副本正在收尾/对话进行中——挂起将由那边解除，此处放弃
        var token = turnLeaseStore.acquire(sessionId, ACQUIRE_WAIT);
        if (token == null) {
            log.info("[confirm-expired] turn lease busy, skip finalize (sid={})", sessionId);
            return false;
        }
        var lease = new TurnLeaseGuard(turnLeaseStore, sessionId, token);
        String replyId = java.util.UUID.randomUUID().toString();
        var turnEnded = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            var results = new ArrayList<Map<String, Object>>(ids.size());
            for (var id : ids) {
                results.add(Map.of("tool_call_id", id, "confirmed", false));
            }
            eventBus.beginTurn(sessionId);
            var flux = runtimeService.resumeWithConfirmEvents(sessionId, null, results);
            flux.subscribe(
                event -> emitFinalizeEvent(sessionId, replyId, lease, turnEnded, event),
                e -> {
                    log.warn("[confirm-expired] finalize turn failed (sid={}): {}", sessionId, e.getMessage());
                    // 竞态常态：另一收尾已消费挂起（无 ASKING 可恢复）——不算故障，租约照常释放
                    TurnFinalizer.endTurn(eventBus, lease, sessionId, turnEnded);
                },
                () -> TurnFinalizer.endTurn(eventBus, lease, sessionId, turnEnded));
            log.info("[confirm-expired] finalize done (sid={}, replyId={})", sessionId, replyId);
            return true;
        } catch (AgentRuntimeService.ConfirmContextNotFoundException e) {
            // 挂起已被（并发收尾/确认）消费：幂等成功
            log.info("[confirm-expired] context already consumed by concurrent finalize (sid={})", sessionId);
            lease.close();
            return true;
        } catch (Exception e) {
            log.warn("[confirm-expired] finalize setup failed (sid={}): {}", sessionId, e.getMessage());
            recordFailure(sessionId, e);
            lease.close();
            return false;
        }
    }

    /** 收尾 turn 的单事件处理（照 ConfirmController.handleEventAndEmit 精简：DENY 恢复段同构语义） */
    private void emitFinalizeEvent(String sessionId, String replyId, TurnLeaseGuard lease,
                                   java.util.concurrent.atomic.AtomicBoolean turnEnded,
                                   io.agentscope.core.event.AgentEvent event) {
        // 丢租约停写（无 SSE sink，收尾事件只落 EventBus；abandon + release 与 stopIfLeaseLost 同款）
        if (lease.isLost()) {
            if (lease.tryMarkLostNotified()) {
                eventBus.abandonSession(sessionId);
                lease.release();
            }
            return;
        }
        // 恢复段若再挂起（模型对拒绝的后续动作触发新 ask）：照常落确认上下文并让出租约——
        // 新挂起是合法 HITL，走正常确认卡流程（截止计时随新挂起重新开始）
        if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent) {
            runtimeService.storeConfirmContext(sessionId, event);
            lease.release();
        }
        eventBus.emit(sessionId, event, replyId, null);
        if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent) {
            eventBus.closeSession(sessionId);
        }
        if (event.getType() == io.agentscope.core.event.AgentEventType.AGENT_END
                && !io.agentmanager.framework.controller.AgentEventSseSerializer.isRemoteForwarded(event)) {
            TurnFinalizer.endTurn(eventBus, lease, sessionId, turnEnded);
        }
    }

    /** 挂起工具 id 收集：state 快照（权威）∪ confirm 行（表兜底），顺序无关（全 false 提交） */
    private List<String> pendingToolCallIds(String sessionId) {
        var ids = new LinkedHashMap<String, Boolean>();
        if (agentStateReader != null) {
            var fullThreadId = makeFullThreadId(sessionId);
            var snapshot = agentStateReader.loadAskingSnapshot(fullThreadId, sessionId);
            // null 防御（契约外返回；mock/异常包装场景）
            for (var call : snapshot != null ? snapshot.asking() : List.<java.util.Map<String, Object>>of()) {
                var id = (String) call.get("tool_call_id");
                if (id != null && !id.isBlank()) {
                    ids.put(id, Boolean.TRUE);
                }
            }
        }
        if (ids.isEmpty()) {
            confirmContextStore.findPending(sessionId).ifPresent(row -> {
                for (var tc : row.toolCalls()) {
                    if (tc.getId() != null && !tc.getId().isBlank()) {
                        ids.put(tc.getId(), Boolean.TRUE);
                    }
                }
            });
        }
        return new ArrayList<>(ids.keySet());
    }

    private String makeFullThreadId(String sessionId) {
        // 与 AgentRuntimeService.makeThreadId 同构：tenantPrefix(slug) + "__" + sid。
        // AgentRuntimeService 未暴露 makeThreadId，这里经 ConfirmContextStore 的前缀兼容
        // SQL 与 AgentStateReader 的 5 形匹配兜底——双候选（fullThreadId, raw）即可命中。
        var prefix = runtimeService.tenantPrefix().replace("/", "-") + "__";
        return prefix + sessionId;
    }

    private void recordFailure(String sessionId, Exception e) {
        if (sessionMessageStore == null) {
            return;
        }
        try {
            var text = String.valueOf(e.getMessage());
            sessionMessageStore.archiveTurnError(sessionId, null,
                "⚠️ 超时确认自动收尾失败：" + (text.length() > 300 ? text.substring(0, 300) : text));
        } catch (Exception ex) {
            log.debug("[confirm-expired] record failure failed (sid={}): {}", sessionId, ex.getMessage());
        }
    }
}
