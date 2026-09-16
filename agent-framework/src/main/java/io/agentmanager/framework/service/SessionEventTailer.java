package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话事件追赶器（durable-sse-multinode-plan §2.4 / §3.4）。
 *
 * <p>承担**观察者路径**：被订阅的 session 的执行可能发生在另一个 Pod 上，因此这里
 * 不读任何进程内状态，只读 Pod 间共享的 DB——{@code session_event}（事件流）
 * 与 {@code turn_lease} / {@code confirm_context}（turn 状态）。
 *
 * <p>与之相对，{@link SessionEventBus} 只服务"拥有执行的那个请求自己的 SSE"，
 * 保证首 token 延迟不受影响。这条分工即设计文档的不变量 I4。
 */
public class SessionEventTailer {

    private static final Logger log = LoggerFactory.getLogger(SessionEventTailer.class);

    /** 终态事件类型：出现即代表 turn 不会再产出新事件 */
    private static final Set<String> TERMINAL_TYPES = Set.of("AGENT_END", "error");

    /** 终止探测的降频间隔：空闲时 ~2s 一次，避免每轮轮询都查 lease + confirm */
    private static final long PROBE_INTERVAL_MS = 2000;

    private final SessionEventStore eventStore;
    private final TurnLeaseStore turnLeaseStore;
    private final AgentRuntimeService runtimeService;

    /** 轮询间隔：无新事件时的查询节奏 */
    private final Duration pollInterval;

    public SessionEventTailer(SessionEventStore eventStore,
                              TurnLeaseStore turnLeaseStore,
                              AgentRuntimeService runtimeService,
                              Duration pollInterval) {
        this.eventStore = eventStore;
        this.turnLeaseStore = turnLeaseStore;
        this.runtimeService = runtimeService;
        this.pollInterval = pollInterval;
    }

    /** turn 状态探测结果 */
    public record TurnProbe(boolean running, boolean finished, boolean interrupted) {
        public static final TurnProbe RUNNING = new TurnProbe(true, false, false);
        public static final TurnProbe FINISHED = new TurnProbe(false, true, false);
        public static final TurnProbe INTERRUPTED = new TurnProbe(false, false, true);
    }

    /**
     * 完整探测：自行取数。
     *
     * <p>顺序刻意如此——lease 被持有时即可短路，不查事件与确认上下文。
     */
    public TurnProbe probe(String sessionId) {
        if (turnLeaseStore.isHeld(sessionId)) {
            return TurnProbe.RUNNING;
        }
        var latest = eventStore.findLatest(sessionId);
        if (latest == null || TERMINAL_TYPES.contains(latest.type())) {
            return TurnProbe.FINISHED;
        }
        // HITL 暂停点：lease 已让出但 turn 未结束。permission_ask 是 turn 边界，
        // 因此对观察者而言同样是"可以正常关流"（设计文档 §3.4.4 的决策）。
        if (runtimeService.findPendingConfirm(sessionId) != null) {
            return TurnProbe.FINISHED;
        }
        // 有事件、非终态、无租约、无待确认：执行副本已崩溃或被抢占
        log.debug("SessionEventTailer: turn interrupted (sid={}, latestSeq={})",
            sessionId, latest.seq());
        return TurnProbe.INTERRUPTED;
    }

    /** 用已取好的数据探测，避免重复查询（/status 端点用） */
    public TurnProbe probe(String sessionId, SessionEventStore.EnvelopedEvent latest,
                           boolean hasPendingConfirm) {
        if (turnLeaseStore.isHeld(sessionId)) {
            return TurnProbe.RUNNING;
        }
        if (latest == null || TERMINAL_TYPES.contains(latest.type())) {
            return TurnProbe.FINISHED;
        }
        if (hasPendingConfirm) {
            return TurnProbe.FINISHED;
        }
        return TurnProbe.INTERRUPTED;
    }
}
