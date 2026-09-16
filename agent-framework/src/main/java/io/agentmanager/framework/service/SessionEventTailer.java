package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    // ===== 观察者事件流 =====

    /**
     * 观察者流：从 afterSeq 回放，然后按游标轮询追赶，直到 turn 终止。
     *
     * <p>全程只读 DB，不使用任何进程内状态——这正是跨副本正确性的来源：被订阅的
     * session 的执行可能发生在另一个 Pod 上，它的 sink 在本 Pod 不可达。
     *
     * <p>完成判定见 {@link #probe(String)}：正常结束补 done 帧；
     * 执行副本崩溃/被抢占补 interrupted 帧。
     */
    public Flux<ServerSentEvent<String>> tail(String sessionId, String replyId, int afterSeq) {
        var cursor = new AtomicInteger(Math.max(afterSeq, -1));

        // afterSeq < 0 表示不回放（与 SessionEventBus.subscribe 的既有语义一致）
        Flux<ServerSentEvent<String>> replay = afterSeq < 0
            ? Flux.empty()
            : eventStore.queryAfter(sessionId, replyId, afterSeq)
                .doOnNext(e -> cursor.set(e.seq()))
                .map(this::toSSE);

        Flux<ServerSentEvent<String>> live = Flux.<ServerSentEvent<String>>create(sink -> {
            long lastProbeAt = 0;   // 0 → 首轮立即探测，避免对已结束的 turn 空等一轮
            while (!sink.isCancelled()) {
                var page = eventStore.queryAfter(sessionId, replyId, cursor.get())
                    .collectList().block();
                if (page == null) page = java.util.List.of();

                boolean sawTerminal = false;
                for (var e : page) {
                    if (sink.isCancelled()) return;
                    sink.next(toSSE(e));
                    cursor.set(e.seq());
                    if (TERMINAL_TYPES.contains(e.type())) sawTerminal = true;
                }

                if (sawTerminal) {
                    sink.next(doneSSE());
                    sink.complete();
                    return;
                }

                // 终止探测降频：空闲时 ~2s 一次，避免每轮轮询都查 lease + confirm
                long now = System.currentTimeMillis();
                if (page.isEmpty() && now - lastProbeAt >= PROBE_INTERVAL_MS) {
                    lastProbeAt = now;
                    var probe = probe(sessionId);
                    if (probe.interrupted()) {
                        sink.next(interruptedSSE());
                        sink.complete();
                        return;
                    }
                    if (probe.finished()) {
                        sink.next(doneSSE());
                        sink.complete();
                        return;
                    }
                }

                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    sink.complete();
                    return;
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());

        return replay.concatWith(live);
    }

    private ServerSentEvent<String> toSSE(SessionEventStore.EnvelopedEvent e) {
        // 与 SessionEventBus.toSSE 保持一致的 payload 形态：把 replyId 注入 payload JSON，
        // 使同一事件无论走执行副本的本地 sink 还是观察者的 DB 追赶，前端拿到的字节一致。
        String data = e.payload();
        if (e.replyId() != null && !e.replyId().isBlank()) {
            try {
                var node = MAPPER.readTree(data);
                if (node != null && node.isObject() && !node.has("replyId")) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("replyId", e.replyId());
                    data = MAPPER.writeValueAsString(node);
                }
            } catch (Exception ex) {
                // 注入失败不阻塞主链路，使用原始 payload
            }
        }
        // id（seq）语义与 SessionEventBus.toSSE 一致，前端可据此记录回放游标
        return ServerSentEvent.<String>builder()
            .data(data)
            .id(String.valueOf(e.seq()))
            .build();
    }

    private static ServerSentEvent<String> doneSSE() {
        return ServerSentEvent.<String>builder().data("{\"type\":\"done\"}").build();
    }

    private static ServerSentEvent<String> interruptedSSE() {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"interrupted\",\"reason\":\"turn_interrupted\"}")
            .build();
    }
}
