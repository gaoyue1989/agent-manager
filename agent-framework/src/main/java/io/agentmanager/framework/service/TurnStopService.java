package io.agentmanager.framework.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 单轮停止请求的裁决（POST /threads/{sessionId}/stop 的业务入口）。
 *
 * <h2>目标轮次解析（按优先级）</h2>
 * <ol>
 *   <li>HITL 待确认（{@code waiting_confirm}）：不属「运行中」，返回 WAITING_CONFIRM，
 *       由调用方提示改用确认卡拒绝（拒绝路径本身即干净终止）。</li>
 *   <li>租约在（有 turn 正在执行）：取最新非终态事件的 replyId——同会话
 *       {@code run(A)+queue(B)} 时优先停正在跑的 A，而不是只取消排队的 B。</li>
 *   <li>租约在但尚未产出任何事件：目标暂不可判定，返回 TURN_NOT_READY（客户端短重试）。</li>
 *   <li>无租约但 {@code turn_intent} 存在（排队中）：用意图行 replyId，排队 loop 收到
 *       标记后直接以 cancelled 收流。</li>
 *   <li>以上皆无：NOTHING_TO_STOP（404，不写任何行）。</li>
 * </ol>
 *
 * <p>写入 {@link TurnStopStore} 失败一律 UNAVAILABLE（端点 503）——不能把存储故障
 * 谎称为「已停止」。
 */
@Service
public class TurnStopService {

    private static final Logger log = LoggerFactory.getLogger(TurnStopService.class);

    /** 停止请求裁决结果 */
    public enum Outcome {
        /** 已写入共享存储，等待属主副本消费 */
        REQUESTED,
        /** 会话没有运行中的 turn（或已终态） */
        NOTHING_TO_STOP,
        /** HITL 挂起中：请走 confirm 拒绝 */
        WAITING_CONFIRM,
        /** 租约在，但本轮尚无事件可定位（客户端短重试） */
        TURN_NOT_READY,
        /** 存储不可用（fail-closed） */
        UNAVAILABLE
    }

    public record Result(Outcome outcome, String replyId) {
        public static Result of(Outcome outcome, String replyId) {
            return new Result(outcome, replyId);
        }
    }

    private final TurnStopStore stopStore;
    private final TurnIntentStore intentStore;
    private final TurnLeaseStore leaseStore;
    private final SessionEventStore eventStore;
    private final AgentRuntimeService runtimeService;

    public TurnStopService(TurnStopStore stopStore,
                           TurnIntentStore intentStore,
                           TurnLeaseStore leaseStore,
                           SessionEventStore eventStore,
                           AgentRuntimeService runtimeService) {
        this.stopStore = stopStore;
        this.intentStore = intentStore;
        this.leaseStore = leaseStore;
        this.eventStore = eventStore;
        this.runtimeService = runtimeService;
    }

    /** 裁决并写入停止请求。 */
    public Result request(String sessionId, String requestedBy) {
        if (sessionId == null || sessionId.isBlank()) {
            return Result.of(Outcome.NOTHING_TO_STOP, null);
        }

        // ① HITL 挂起：不是运行中的 turn（拒绝确认走 confirm 通道，语义更干净）
        try {
            if (runtimeService != null && runtimeService.findPendingConfirm(sessionId) != null) {
                return Result.of(Outcome.WAITING_CONFIRM, null);
            }
        } catch (Exception e) {
            // 读失败不当作「无挂起」：继续走后续解析，由存储读写失败统一兜底
            log.warn("TurnStopService: pending-confirm probe failed for sid={}: {}",
                sessionId, e.getMessage());
        }

        // ② 执行权裁决：租约在 = 有 turn 正在跑（优先停它）；否则看排队意图
        boolean held;
        try {
            held = leaseStore.isHeldStrict(sessionId);
        } catch (Exception e) {
            // 不能把存储抖动判成「无活跃 turn」→ 404；按不可判定返回 503
            log.warn("TurnStopService: lease probe failed for sid={}: {}", sessionId, e.getMessage());
            return Result.of(Outcome.UNAVAILABLE, null);
        }

        String target = null;
        if (held) {
            // ③ 执行中：用最新非终态事件的 replyId 定位本轮 turn
            SessionEventStore.EnvelopedEvent latest;
            try {
                latest = eventStore.findLatestStrict(sessionId);
            } catch (Exception e) {
                log.warn("TurnStopService: latest event read failed for sid={}: {}",
                    sessionId, e.getMessage());
                return Result.of(Outcome.UNAVAILABLE, null);
            }
            if (latest != null && !SessionEventTailer.isTerminalType(latest.type())
                    && latest.replyId() != null && !latest.replyId().isBlank()) {
                target = latest.replyId();
            }
            // ④ 租约在但事件还没产出（刚抢到锁/准备段）：目标暂不可判定
            if (target == null) {
                return Result.of(Outcome.TURN_NOT_READY, null);
            }
        } else {
            // ⑤ 无租约：排队 turn 还没有 session_event，用意图行 replyId
            var intent = intentStore != null ? intentStore.find(sessionId) : null;
            if (intent != null && !intent.failed()
                    && intent.replyId() != null && !intent.replyId().isBlank()) {
                target = intent.replyId();
            }
            if (target == null) {
                return Result.of(Outcome.NOTHING_TO_STOP, null);
            }
        }

        if (stopStore.request(sessionId, target, requestedBy)
                != TurnStopStore.RequestOutcome.REQUESTED) {
            return Result.of(Outcome.UNAVAILABLE, target);
        }
        log.info("[turn-stop] requested: sid={}, rid={}, by={}", sessionId, target, requestedBy);
        return Result.of(Outcome.REQUESTED, target);
    }

    /** 排队 turn 的短轮询：本会话是否有指向本 replyId 的停止请求。 */
    public boolean matches(String sessionId, String replyId) {
        var req = stopStore.find(sessionId);
        return req != null && replyId != null && replyId.equals(req.replyId());
    }

    /** 消费/清理本轮的停止请求（幂等；不匹配的行走不掉）。 */
    public void clear(String sessionId, String replyId) {
        stopStore.consume(sessionId, replyId);
    }
}
