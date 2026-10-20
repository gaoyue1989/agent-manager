package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;

import io.agentmanager.framework.controller.AgentEventSseSerializer;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 会话事件追赶器（durable-sse-multinode-plan §2.4 / §3.4）。
 *
 * <p>承担**观察者路径**：被订阅的 session 的执行可能发生在另一个 Pod 上，因此这里
 * 不读任何进程内状态，只读 Pod 间共享的存储——{@code session_event}（事件流，在 Redis）
 * 与 {@code turn_lease} / {@code confirm_context}（turn 状态，在 MySQL）。
 *
 * <p>与之相对，{@link SessionEventBus} 只服务"拥有执行的那个请求自己的 SSE"，
 * 保证首 token 延迟不受影响。这条分工即设计文档的不变量 I4。
 */
public class SessionEventTailer {

    private static final Logger log = LoggerFactory.getLogger(SessionEventTailer.class);

    /** 终态事件类型：出现即代表 turn 不会再产出新事件 */
    private static final Set<String> TERMINAL_TYPES = Set.of("AGENT_END", "error");

    /**
     * 该事件类型是否为 turn 终态。
     *
     * <p>终态判据只允许此一处：{@code /status} 的 completed 判定与 {@link #probe} 收流判定
     * 必须同口径。否则 turn 以 {@code error} 结束时，{@code /status} 会落进 {@code idle}
     * （刷新后前端既不续传、也看不到失败），与对话流的「error 帧 + 关流」不一致。
     */
    public static boolean isTerminalType(String type) {
        return type != null && TERMINAL_TYPES.contains(type);
    }

    /** 终止探测的基准间隔：空页时 ~2s 一次，避免每轮轮询都查 lease + confirm（持续空闲后降为 10s） */
    static final long PROBE_INTERVAL_MS = 2000;

    /** 持续无业务事件超过该时长后，终止探测降为 {@link #IDLE_PROBE_INTERVAL_MS} */
    static final long IDLE_PROBE_AFTER_MS = 60_000;

    /**
     * 长静默期的终止探测间隔。
     *
     * <p>每个空闲订阅者每拍都要查一次 MySQL（lease）与 confirm：2s 节奏在大量长连接下是
     * N/2s 的库压力。长工具调用/长推理期间事件本来就不来，而租约由执行方 20s 续租、
     * 崩溃最迟 60s 过期——把探测放宽到 10s 只把「中断/接管」的发现延迟从 2s 提到 10s，
     * 仍在崩溃检测窗口内；事件拉取节奏（300ms→2s 退避）不变，正常输出到达仍是实时的。
     */
    static final long IDLE_PROBE_INTERVAL_MS = 10_000;

    /** 按「距最后一个业务事件」的时长选择探测间隔（包私有：表驱动测试） */
    static long probeIntervalMs(long idleMs) {
        return idleMs >= IDLE_PROBE_AFTER_MS ? IDLE_PROBE_INTERVAL_MS : PROBE_INTERVAL_MS;
    }

    /**
     * 连续 emptyStreak 次空页后，本轮循环应 sleep 的毫秒数（A2 空闲退避）。
     *
     * <p>退避序列（base=300）：600 → 1200 → 2000(封顶=PROBE_INTERVAL_MS) → 2000 …
     * 空闲连接的 XRANGE 查询从 ~3.3 QPS 降到 ~0.5 QPS；非空页把 streak 归零，
     * 下一轮即回基频——事件一旦开始流式产出，300ms 节奏不受退避影响。
     *
     * <p>结果永不低于 baseMs：tailPollMs 可配（AGENT_SSE_TAIL_POLL_MS，AgentManagerProperties），
     * 若配置超过封顶值（如 5000），先 min 再 max 的钳制保证退避不会「反而提速」
     * （否则序列 5000→2000→2000 倒挂）。
     *
     * @package-private 静态纯函数，便于表驱动测试
     */
    static long pollSleepMs(long baseMs, int emptyStreak) {
        if (emptyStreak <= 0) {
            return baseMs;
        }
        long backoff = baseMs << Math.min(emptyStreak, 10);   // 2^10 封顶防溢出
        return Math.max(baseMs, Math.min(backoff, PROBE_INTERVAL_MS));
    }

    private final SessionEventStore eventStore;
    private final TurnLeaseStore turnLeaseStore;
    private final AgentRuntimeService runtimeService;
    /** 排队意图（可空：单测/嵌入式无该存储时退回纯租约探测） */
    private final TurnIntentStore turnIntentStore;

    /** 轮询间隔：无新事件时的查询节奏 */
    private final Duration pollInterval;

    /**
     * 心跳间隔：长时间无业务事件时补发 SSE comment 帧。
     *
     * <p>与 {@link SessionEventBus#subscribe} 的心跳同源——入口代理（Nginx/CDN）对长时间
     * 静默的连接有读超时（默认 60s）。而观察者路径恰恰以长静默为常态（执行发生在另一个
     * 副本上、期间可能是一个长工具调用），没有心跳就会被中途切断。
     */
    private final Duration heartbeatInterval;

    public SessionEventTailer(SessionEventStore eventStore,
                              TurnLeaseStore turnLeaseStore,
                              AgentRuntimeService runtimeService,
                              Duration pollInterval) {
        this(eventStore, turnLeaseStore, runtimeService, pollInterval, Duration.ofSeconds(20), null);
    }

    public SessionEventTailer(SessionEventStore eventStore,
                              TurnLeaseStore turnLeaseStore,
                              AgentRuntimeService runtimeService,
                              Duration pollInterval,
                              Duration heartbeatInterval) {
        this(eventStore, turnLeaseStore, runtimeService, pollInterval, heartbeatInterval, null);
    }

    public SessionEventTailer(SessionEventStore eventStore,
                              TurnLeaseStore turnLeaseStore,
                              AgentRuntimeService runtimeService,
                              Duration pollInterval,
                              Duration heartbeatInterval,
                              TurnIntentStore turnIntentStore) {
        this.eventStore = eventStore;
        this.turnLeaseStore = turnLeaseStore;
        this.runtimeService = runtimeService;
        this.pollInterval = pollInterval;
        this.heartbeatInterval = heartbeatInterval;
        this.turnIntentStore = turnIntentStore;
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
        if (latest == null || isTerminalType(latest.type())) {
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
        if (latest == null || isTerminalType(latest.type())) {
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
     * <p>全程只读共享存储，不使用任何进程内状态——这正是跨副本正确性的来源：被订阅的
     * session 的执行可能发生在另一个 Pod 上，它的 sink 在本 Pod 不可达。
     *
     * <p>完成判定见 {@link #probe(String)}：正常结束补 done 帧；
     * 执行副本崩溃/被抢占补 interrupted 帧。
     */
    public Flux<ServerSentEvent<String>> tail(String sessionId, String replyId, int afterSeq) {
        var cursor = new AtomicInteger(Math.max(afterSeq, -1));

        // 订阅建立时先看一次排队意图：排队中的 turn 尚无事件，replyId 不在索引里是正常状态，
        // 不能让 replay/live 的 missing-index 兜底把长会话全量扫描一遍（排队期间每拍都会发生）。
        var startIntent = turnIntentStore != null ? turnIntentStore.find(sessionId) : null;
        boolean intentMatches = startIntent != null && replyId != null
            && replyId.equals(startIntent.replyId());
        boolean startQueued = intentMatches && !startIntent.failed();
        String startError = intentMatches && startIntent.failed()
            ? (startIntent.error() != null && !startIntent.error().isBlank()
                ? startIntent.error() : "turn_failed")
            : null;

        // afterSeq < 0 表示不回放（与 SessionEventBus.subscribe 的既有语义一致）
        Flux<ServerSentEvent<String>> replay = afterSeq < 0
            ? Flux.empty()
            : (startQueued || startError != null
                ? eventStore.queryAfter(sessionId, replyId, afterSeq, false)
                : eventStore.queryAfter(sessionId, replyId, afterSeq))
                .doOnNext(e -> cursor.set(e.seq()))
                .map(AgentEventSseSerializer::toSseFrame);

        // 实时追赶：按下游需求逐帧拉取（Flux.generate 每次请求发一帧）。
        // 不用 Flux.create 手写死循环：Spring MVC 的 SseEmitterSubscriber 在「发送完成」后
        // 才在异步线程 request(1)，而 Flux.create 的生成器在另一线程上会先于该 request 把
        // 整页事件塞进队列——跨线程 request 与生产者暂停叠加时，队列里的待发帧会一直等到
        // 下一次生产者写入才被 drain（实测：续传只在回放后吐第一帧，后续 delta 要等 turn
        // 结束才一次性到达）。generate 把「等事件」放在下游请求触发的调用里，天然按需。
        var liveStateRef = new java.util.concurrent.atomic.AtomicReference<LiveState>();
        Flux<ServerSentEvent<String>> live = Flux.<ServerSentEvent<String>, LiveState>generate(() -> {
            var st = new LiveState();
            st.queuedReply = startQueued;
            st.errorPending = startError;
            liveStateRef.set(st);
            return st;
        }, (st, sink) -> {
            while (!st.cancelled) {
                // 缓冲里仍有事件：先发完（含竞态兜底补发的尾部事件），再收流
                if (!st.buffer.isEmpty()) {
                    var e = st.buffer.poll();
                    sink.next(AgentEventSseSerializer.toSseFrame(e));
                    cursor.set(e.seq());
                    st.lastFrameAt = System.currentTimeMillis();
                    st.lastDataAt = st.lastFrameAt;
                    if (isTerminalType(e.type())) {
                        st.donePending = true;
                    }
                    return st;
                }
                if (st.errorPending != null) {
                    // 排队 turn 失败（超时/建立失败）：与对话流的 error 帧同词表，收流
                    sink.next(errorSSE(st.errorPending));
                    sink.complete();
                    return st;
                }
                if (st.donePending) {
                    sink.next(doneSSE());
                    sink.complete();
                    return st;
                }
                if (st.interruptedPending) {
                    sink.next(interruptedSSE());
                    sink.complete();
                    return st;
                }

                // 排队中的 replyId 允许「索引里还没有」：不触发重建/全流扫描，安静等待 turn 启动
                var page = (st.queuedReply
                    ? eventStore.queryAfter(sessionId, replyId, cursor.get(), false)
                    : eventStore.queryAfter(sessionId, replyId, cursor.get()))
                    .collectList().block();
                if (page == null) page = java.util.List.of();
                st.emptyStreak = page.isEmpty() ? st.emptyStreak + 1 : 0;
                if (!page.isEmpty()) {
                    st.buffer.addAll(page);
                    continue;
                }

                // 终止探测降频：空闲时 ~2s 一次，避免每轮轮询都查 lease + confirm
                long now = System.currentTimeMillis();
                if (now - st.lastProbeAt >= probeIntervalMs(now - st.lastDataAt)) {
                    st.lastProbeAt = now;
                    // 排队意图优先：本轮 turn 还没产出事件，租约可能被上一轮持有（或刚释放）。
                    //  - QUEUED：本轮确定会开跑 → 按 RUNNING 处理，跳过通用 probe（否则会被
                    //    误判 interrupted/finished）；
                    //  - FAILED：直接补 error 帧收流，不必等上一轮结束。
                    var intent = turnIntentStore != null ? turnIntentStore.find(sessionId) : null;
                    boolean matches = intent != null && replyId != null
                        && replyId.equals(intent.replyId());
                    st.queuedReply = matches && !intent.failed();
                    if (matches && intent.failed()) {
                        st.errorPending = intent.error() != null && !intent.error().isBlank()
                            ? intent.error() : "turn_failed";
                        continue;
                    }
                    var probe = st.queuedReply ? TurnProbe.RUNNING : probe(sessionId);
                    if (probe.interrupted()) {
                        st.interruptedPending = true;
                        continue;
                    }
                    if (probe.finished()) {
                        // 竞态兜底：终止行可能恰好落在上面那次 queryAfter 与本次 probe 之间。
                        // 那时 page 为空、probe 却已判定结束——若不补一次追赶查询，终止事件
                        // 自身（尤其是 error）会被静默丢弃，客户端只会看到一个"正常完成"。
                        emitRemainingInto(st.buffer, sessionId, replyId, cursor);
                        st.donePending = true;
                        continue;
                    }
                }

                // 心跳：两次 probe 之间可能有任意长的静默，comment 帧不触发前端 onmessage，
                // 只用于重置入口代理的读超时计时器（否则长静默的观察者流会被中途切断）。
                if (now - st.lastFrameAt >= heartbeatInterval.toMillis()) {
                    sink.next(heartbeatSSE());
                    st.lastFrameAt = now;
                    return st;
                }

                try {
                    // 空闲退避（A2）：连续空页后逐步放宽到 2s（= PROBE_INTERVAL_MS）封顶，
                    // 有新事件（非空页）streak 已归零、下一轮即回基频。终态判定节奏：
                    // 探测门控原样保留，稳态下空页轮询周期 == 探测周期，与现状一致；
                    // 过渡期第二次探测一次性推迟 ~1.7s（首拍 600/1200ms 均不满足 2s 门控）。
                    Thread.sleep(pollSleepMs(pollInterval.toMillis(), st.emptyStreak));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    sink.complete();
                    return st;
                }
            }
            return st;
        }).doOnCancel(() -> {
            var st = liveStateRef.get();
            if (st != null) st.cancelled = true;
        });

        // 整个观察者流（含 replay 的分页读）都在 boundedElastic 上跑：replay 是阻塞查询，
        // 留在 servlet 线程会把响应提交前的线程占满（全量回放时尤其明显）。
        return replay.concatWith(live).subscribeOn(Schedulers.boundedElastic());
    }

    /** 实时追赶的每订阅状态（Flux.generate 的 state，仅订阅线程访问）。 */
    private static final class LiveState {
        final java.util.ArrayDeque<SessionEventStore.EnvelopedEvent> buffer = new java.util.ArrayDeque<>();
        /** 0 → 首轮立即探测，避免对已结束的 turn 空等一轮 */
        long lastProbeAt;
        long lastFrameAt = System.currentTimeMillis();
        /** 最后一个业务事件时刻（心跳不算）：长静默判定，决定探测是否降频 */
        long lastDataAt = System.currentTimeMillis();
        /** 连续空页计数（A2 空闲退避）：空页 +1，非空页归零 */
        int emptyStreak;
        boolean donePending;
        boolean interruptedPending;
        /** 请求的 replyId 对应一个排队中的 turn（尚无事件；查询不做 missing-index 兜底） */
        boolean queuedReply;
        /** 非空时先补一帧 error 再收流（排队 turn 失败） */
        String errorPending;
        /** 订阅被取消：让阻塞中的轮询在下一拍退出，不再等心跳到期 */
        volatile boolean cancelled;
    }

    /**
     * 补发一次追赶查询的结果：用于 probe 判定 turn 已结束后的最后一跳。
     *
     * <p>不做循环——终止行至多带来一批尾部事件，取一次即可；取不到就正常补 done 帧。
     */
    private void emitRemainingInto(java.util.Deque<SessionEventStore.EnvelopedEvent> buffer,
                                   String sessionId, String replyId, AtomicInteger cursor) {
        var page = eventStore.queryAfter(sessionId, replyId, cursor.get()).collectList().block();
        if (page == null) return;
        buffer.addAll(page);
    }

    private static ServerSentEvent<String> heartbeatSSE() {
        return ServerSentEvent.<String>builder().comment("hb").build();
    }

    private static ServerSentEvent<String> doneSSE() {
        return ServerSentEvent.<String>builder().data("{\"type\":\"done\"}").build();
    }

    private static ServerSentEvent<String> interruptedSSE() {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"interrupted\",\"reason\":\"turn_interrupted\"}")
            .build();
    }

    private static ServerSentEvent<String> errorSSE(String error) {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"error\",\"error\":"
                + AgentEventSseSerializer.jsonEsc(error) + "}")
            .build();
    }
}
