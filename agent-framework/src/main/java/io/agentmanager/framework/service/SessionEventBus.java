package io.agentmanager.framework.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.controller.AgentEventSseSerializer;
import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 会话事件总线（durable-sse-plan §3.2 核心组件）。
 *
 * <p>解耦 agent 执行与 SSE 连接的生命周期：
 * <ul>
 *   <li>agent 事件写入 EventBus → 持久化 + 广播给所有 SSE 订阅者</li>
 *   <li>SSE 连接断开不影响 agent 执行</li>
 *   <li>前端可通过 {@code GET /subscribe?afterSeq=N} 回放增量事件并续传</li>
 * </ul>
 *
 * <p>当前实现为单实例（ConcurrentHashMap），多实例部署需替换为 Redis Pub/Sub
 * （接口已预留，见 §6）。
 */
@Service
public class SessionEventBus {

    private static final Logger log = LoggerFactory.getLogger(SessionEventBus.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** session_id → Sinks.Many<EnvelopedEvent> */
    private final ConcurrentHashMap<String, Sinks.Many<SessionEventStore.EnvelopedEvent>> sinks =
        new ConcurrentHashMap<>();

    private final SessionEventStore eventStore;

    /** 心跳间隔：无业务事件时每 N 秒发一次 SSE comment 帧 */
    private final Duration heartbeatInterval;

    /** Sinks 过期清理延迟：最后一个订阅者离开后多久清理 Sinks */
    private final Duration sinksEvictionDelay;

    /** Sinks 缓冲区大小（每个 session） */
    private final int bufferSize;

    /** session_id → 最后活跃时间（用于过期清理） */
    private final ConcurrentHashMap<String, Instant> lastActiveAt = new ConcurrentHashMap<>();

    public SessionEventBus(SessionEventStore eventStore,
                           Duration heartbeatInterval,
                           Duration sinksEvictionDelay,
                           int bufferSize) {
        this.eventStore = eventStore;
        this.heartbeatInterval = heartbeatInterval;
        this.sinksEvictionDelay = sinksEvictionDelay;
        this.bufferSize = bufferSize;
    }

    public SessionEventBus(SessionEventStore eventStore) {
        this(eventStore, Duration.ofSeconds(20), Duration.ofMinutes(5), 256);
    }

    // ===== 事件发布（agent 执行侧调用） =====

    /**
     * 发射一个 agent 事件。
     * 1. 持久化到 session_event 表
     * 2. 包装为 EnvelopedEvent 广播给所有 SSE 订阅者
     *
     * @return 分配的 seq；-1 表示持久化失败（但实时广播仍会尝试）
     */
    public int emit(String sessionId, AgentEvent event, String replyId) {
        String payload = AgentEventSseSerializer.payload(event);
        String type = event.getType().name();
        int seq = eventStore.append(sessionId, replyId, type, payload);

        if (log.isDebugEnabled()) {
            log.debug("[EventBus] emit: sid={}, seq={}, type={}, replyId={}", sessionId, seq, type, replyId);
        }

        var sink = sinks.get(sessionId);
        if (sink != null) {
            var enveloped = new SessionEventStore.EnvelopedEvent(
                seq > 0 ? seq : 0, type, payload, replyId);
            var result = sink.tryEmitNext(enveloped);
            if (result.isFailure()) {
                log.debug("SessionEventBus: emit to sink failed (sid={}, result={})",
                    sessionId, result);
            }
        }

        touchActive(sessionId);
        return seq;
    }

    /**
     * 发射合成事件（如 file_ready、waiting、error）。
     * 同上流程，但不来自 AgentEvent。
     */
    public int emitSynthetic(String sessionId, String replyId, String type, String payload) {
        int seq = eventStore.append(sessionId, replyId, type, payload);

        log.debug("[EventBus] emitSynthetic: sid={}, seq={}, type={}, replyId={}", sessionId, seq, type, replyId);

        var sink = sinks.get(sessionId);
        if (sink != null) {
            var enveloped = new SessionEventStore.EnvelopedEvent(
                seq > 0 ? seq : 0, type, payload, replyId);
            sink.tryEmitNext(enveloped);
        }

        touchActive(sessionId);
        return seq;
    }

    // ===== SSE 订阅（控制器侧调用） =====

    /**
     * 创建新 SSE 订阅。
     *
     * @param sessionId 会话 ID
     * @param afterSeq  回放起点（0 = 不回放，从当前开始）
     * @param replyId   turn 标识（可选，null = 不限 turn）
     * @return Flux<ServerSentEvent<String>>
     */
    public Flux<ServerSentEvent<String>> subscribe(String sessionId, int afterSeq, String replyId) {
        var sink = ensureSink(sessionId);

        // 1. 回放历史事件（afterSeq < 0 时不回放；afterSeq=0 表示从头回放）
        Flux<ServerSentEvent<String>> replay = Flux.defer(() -> {
            if (afterSeq < 0) return Flux.empty();
            return eventStore.queryAfter(sessionId, replyId, afterSeq)
                .map(this::toSSE);
        });

        // 2. 实时事件流（过滤 replyId）
        Flux<ServerSentEvent<String>> live = sink.asFlux()
            .filter(e -> replyId == null || replyId.isBlank() || replyId.equals(e.replyId()))
            .map(this::toSSE);

        // 3. 心跳流：SSE comment 帧，不触发前端 onmessage，但重置 Nginx 超时计时器
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(heartbeatInterval)
            .map(i -> ServerSentEvent.<String>builder()
                .comment("hb")
                .build());

        // 4. 完成信号：live 流结束时终止合并流（否则 merge 永远不会完成，因为 heartbeat 无限）
        Mono<Void> completionSignal = sink.asFlux().then();

        // 先回放完，再合并实时流 + 心跳，实时流结束时自动关闭
        return replay.concatWith(live.mergeWith(heartbeat).takeUntilOther(completionSignal))
            .doOnSubscribe(s -> touchActive(sessionId))
            .doOnCancel(() -> log.debug("SessionEventBus: subscription cancelled (sid={})", sessionId))
            .doOnError(e -> log.debug("SessionEventBus: subscription error (sid={}): {}",
                sessionId, e.getMessage()));
    }

    /**
     * 确保指定 session 的 Sinks 存在（computeIfAbsent 语义）。
     * 在 agent 开始执行前调用，保证事件有输出通道。
     */
    public Sinks.Many<SessionEventStore.EnvelopedEvent> ensureSink(String sessionId) {
        return sinks.computeIfAbsent(sessionId, k ->
            Sinks.many().multicast().onBackpressureBuffer(bufferSize));
    }

    /**
     * 开始一个 turn：播种 seq 计数器 + 确保 sink 存在。
     *
     * <p>必须在**获得 turn_lease 之后**调用——seq 计数器要从 DB 当前最大值续起
     * （见 SessionEventStore.seedSeq）。两个动作合在一起是因为它们同属
     * "为本 turn 准备输出通道与序号空间"这一件事。
     */
    public Sinks.Many<SessionEventStore.EnvelopedEvent> beginTurn(String sessionId) {
        eventStore.seedSeq(sessionId);
        return ensureSink(sessionId);
    }

    /**
     * turn 结束时关闭 session 的 Sinks。
     * 所有 SSE 订阅者收到 onComplete → 流关闭 → 前端显示完成。
     * 同时刷出待落库行并释放 seq 计数器。
     */
    public void closeSession(String sessionId) {
        eventStore.finishTurn(sessionId);
        var sink = sinks.remove(sessionId);
        lastActiveAt.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
            log.info("[EventBus] session closed (sid={})", sessionId);
        }
    }

    /**
     * 查询 session 当前的 turn 状态（status 端点使用）。
     */
    public TurnStatus turnStatus(String sessionId) {
        // 检查是否有活跃的 EventBus sink
        boolean hasSink = sinks.containsKey(sessionId);

        // 检查最新事件
        var latest = eventStore.findLatest(sessionId);

        if (hasSink) {
            // EventBus 有 sink，说明 agent 正在执行
            return TurnStatus.WORKING;
        }

        if (latest != null) {
            if ("AGENT_END".equals(latest.type())) {
                return TurnStatus.COMPLETED;
            }
            // 有事件但无 sink：可能是 agent 执行中间断连
            // 需结合 turn_lease 判断
        }

        return TurnStatus.IDLE;
    }

    /** 获取指定 session 当前的 replyId（subscribe 时用于过滤） */
    public String currentReplyId(String sessionId) {
        var latest = eventStore.findLatest(sessionId);
        return latest != null ? latest.replyId() : null;
    }

    /** 获取底层 EventStore（控制器 status 端点用） */
    public SessionEventStore getEventStore() {
        return eventStore;
    }

    /**
     * 仅回放历史事件，不订阅实时流（用于已完成 turn 的 subscribe 请求）。
     *
     * @param sessionId 会话 ID
     * @param replyId   turn 标识（可选）
     * @param afterSeq  回放起点（0 = 从头回放，-1 = 不回放）
     * @return Flux<ServerSentEvent<String>>
     */
    public Flux<ServerSentEvent<String>> replayOnly(String sessionId, String replyId, int afterSeq) {
        if (afterSeq < 0) return Flux.empty();
        return eventStore.queryAfter(sessionId, replyId, afterSeq)
            .map(this::toSSE);
    }

    // ===== 过期清理 =====

    /**
     * 清理过期的 Sinks（无订阅者且超过 evictionDelay 未活跃）。
     *
     * <p>独立调度（60s 一次）——此前仅挂在 SessionCleanupService 的每日 03:00 cron 上，
     * 导致任何漏掉 closeSession 的 sink 会存活近 24 小时，跨副本 subscribe 也会
     * 因此悬挂同样长的时间。见 durable-sse-multinode-plan §3.2.3。
     */
    @Scheduled(fixedDelay = 60_000)
    public int evictStaleSinks() {
        var now = Instant.now();
        int count = 0;
        var it = sinks.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var lastActive = lastActiveAt.get(entry.getKey());
            if (lastActive != null && lastActive.plus(sinksEvictionDelay).isBefore(now)) {
                // 检查是否仍有订阅者
                // Sinks.Many 无直接方式检查订阅者数量，用 tryEmitComplete 尝试关闭
                it.remove();
                lastActiveAt.remove(entry.getKey());
                entry.getValue().tryEmitComplete();
                count++;
            }
        }
        if (count > 0) {
            log.info("SessionEventBus: evicted {} stale sink(s)", count);
        }
        return count;
    }

    // ===== 内部辅助 =====

    private void touchActive(String sessionId) {
        lastActiveAt.put(sessionId, Instant.now());
    }

    private ServerSentEvent<String> toSSE(SessionEventStore.EnvelopedEvent e) {
        String data = e.payload();
        // 将 replyId 注入 payload JSON，使前端合成事件（file_ready/waiting/error）也能获得 replyId
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
        return ServerSentEvent.<String>builder()
            .data(data)
            .id(String.valueOf(e.seq()))
            .build();
    }

    /** turn 状态枚举 */
    public enum TurnStatus {
        WORKING,    // agent 正在执行
        COMPLETED,  // turn 已完成
        IDLE        // 无活跃 turn
    }
}
