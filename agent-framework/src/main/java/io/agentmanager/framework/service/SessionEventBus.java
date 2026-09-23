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
     * 2. 包装为 EnvelopedEvent 广播给所有 SSE 订阅者（持久化失败时**不广播**，见下）
     *
     * @return 分配的 seq；-1 表示持久化失败——该事件永不落库，广播它会产生一条
     *         回放（断连续传、/subscribe 重放）永远补不出来的实时帧，因此跳过广播
     *         （失败细节由 RedisEventLog.fail 的分级日志承担）
     */
    public int emit(String sessionId, AgentEvent event, String replyId) {
        return emit(sessionId, event, replyId, null);
    }

    /**
     * 同上，但允许事件发源地覆写序列化结果——MCP Apps 的 TOOL_CALL_START 由此携带
     * ui 元数据（mcp-apps-extension-plan §4：发源地查 McpToolRegistrar 后传入）。
     * payloadOverride 为 null 时按原词表序列化，行为与 3 参版本完全一致。
     */
    public int emit(String sessionId, AgentEvent event, String replyId, String payloadOverride) {
        String payload = payloadOverride != null ? payloadOverride : AgentEventSseSerializer.payload(event);
        // replyId 注入前移到写路径（A1）：落库 p 字段与广播 data 从此同源（同一份字符串），
        // 读端对存量行（p 内无 replyId）兜底注入即可，不再逐帧重序列化
        payload = AgentEventSseSerializer.withReplyId(payload, replyId);
        String type = event.getType().name();
        int seq = eventStore.append(sessionId, replyId, type, payload);

        if (log.isDebugEnabled()) {
            log.debug("[EventBus] emit: sid={}, seq={}, type={}, replyId={}", sessionId, seq, type, replyId);
        }

        if (seq >= 1) {
            var sink = sinks.get(sessionId);
            if (sink != null) {
                var enveloped = new SessionEventStore.EnvelopedEvent(seq, type, payload, replyId);
                var result = sink.tryEmitNext(enveloped);
                if (result.isFailure()) {
                    log.debug("SessionEventBus: emit to sink failed (sid={}, result={})",
                        sessionId, result);
                }
            }
        } else {
            // 持久化失败（seq=-1）：不广播。实时渲染一条回放永远补不出来的帧，只会让
            // 实时与回放两份视图分叉；这里只 debug 记一笔，失败细节已由 RedisEventLog.fail
            // 按 ERROR/WARN 分级打过，避免 Redis 故障期每事件一条的重复噪音（A3）
            log.debug("[EventBus] emit skipped broadcast (persist failed): sid={}, type={}, replyId={}",
                sessionId, type, replyId);
        }

        touchActive(sessionId);
        return seq;
    }

    /**
     * 发射合成事件（如 file_ready、waiting、error）。
     * 同上流程，但不来自 AgentEvent。
     */
    public int emitSynthetic(String sessionId, String replyId, String type, String payload) {
        // 同 emit：注入前移到写路径，落库与广播共用同一份字符串（A1）
        payload = AgentEventSseSerializer.withReplyId(payload, replyId);
        int seq = eventStore.append(sessionId, replyId, type, payload);

        log.debug("[EventBus] emitSynthetic: sid={}, seq={}, type={}, replyId={}", sessionId, seq, type, replyId);

        if (seq >= 1) {
            var sink = sinks.get(sessionId);
            if (sink != null) {
                var enveloped = new SessionEventStore.EnvelopedEvent(seq, type, payload, replyId);
                sink.tryEmitNext(enveloped);
            }
        } else {
            // 同 emit（A3）：持久化失败不广播，不留一条回放永远补不出来的实时帧
            log.debug("[EventBus] emitSynthetic skipped broadcast (persist failed): sid={}, type={}, replyId={}",
                sessionId, type, replyId);
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
                .map(AgentEventSseSerializer::toSseFrame);
        });

        // 2. 实时事件流（过滤 replyId）
        Flux<ServerSentEvent<String>> live = sink.asFlux()
            .filter(e -> replyId == null || replyId.isBlank() || replyId.equals(e.replyId()))
            .map(AgentEventSseSerializer::toSseFrame);

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
     * 租约丢失时的会话收尾：**丢弃**未落库的缓冲 + 释放 seq 计数器 + 收流。
     *
     * <p>不能复用 {@link #closeSession}：后者走 {@code finishTurn}（刷出该 session 的缓冲），
     * 会把缓冲里那些按「本副本仍持锁」分配的 seq 写下去——那正是丢租约要防的事。
     *
     * <p>sink 关掉是为了让连着的订阅者正常收到 onComplete 结束这次流。turn 本身在被接管的
     * 副本上继续，用户重连后由观察者路径接手新 owner 的输出。
     */
    public void abandonSession(String sessionId) {
        eventStore.abandonTurn(sessionId);
        var sink = sinks.remove(sessionId);
        lastActiveAt.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
            // 只在真的拆掉了 sink 时打日志：丢锁后 stopIfLeaseLost 先拆一次，
            // 源流随后跑完时 endTurn 还会再来一次（幂等空转），不该重复刷 WARN
            log.warn("[EventBus] session abandoned after lease loss (sid={})", sessionId);
        }
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
                // 补齐上面 javadoc 里的「无订阅者」条件：静默 ≠ 死掉。长工具调用期间
                // 可以有数分钟没有任何事件，此时执行方自己的订阅仍在；sink 一旦被清掉，
                // owner 的 SSE 会在 turn 中途无声结束（后续事件照常落库，但本 Pod 不再广播）。
                if (entry.getValue().currentSubscriberCount() > 0) {
                    continue;
                }
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
}
