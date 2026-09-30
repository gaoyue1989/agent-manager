package io.agentmanager.framework.service.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.agentscope.extensions.agentprotocol.AgentProtocolEventBus;
import io.agentscope.extensions.agentprotocol.AgentProtocolTaskEventBus;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 协议任务事件总线 Redis Streams 实现（travel-fulfillment 设计 §18.3，多副本 G2 治理）。
 *
 * <p>替换 SDK 默认内存总线（{@link AgentProtocolTaskEventBus}，replay 256 副本本地）：
 * 事件落 Redis 后 member 任意副本可订阅/重放 {@code /tasks/{id}/events}，跨重启可回放
 * （SDK controller 的 {@code from_seq}/Last-Event-ID 续传直接受益）。
 *
 * <p><b>存储形状</b>（全部经 {@link RedisConnectionFacade#key(String)} 加服务前缀）：
 * <ul>
 *   <li>{@code proto:task:{id}:events} — 事件流，条目 ID = 显式 {@code <seq>-0}；</li>
 *   <li>{@code proto:task:{id}:seq} — INCR 发号器；</li>
 *   <li>{@code proto:task:{id}:done} — 终态标记（complete 写入，tail 据此收流）。</li>
 * </ul>
 * cluster 模式下 taskId 包 hash tag（三键同 slot，为未来单键 Lua 预留）。
 *
 * <p><b>硬约束（复刻 RedisEventLog 纪律）</b>：显式发号 + 显式 ID XADD——绝不用
 * {@code XADD key *}（自动 ID 是毫秒时间戳，会与 {@code <seq>-0} 发号空间冲突，
 * 之后每条显式 ID XADD 都被原子拒绝）；留存用 EXPIRE，不用 XTRIM。INCR 与 XADD
 * 非原子，崩溃间隙只产生 seq 缺口（订阅按范围读，缺口无害）。
 *
 * <p><b>fail-soft</b>：Redis 缺失（facade null）/运行期异常 → 降级进程内内存总线
 * （复刻 RedisEventLog.fail 分级日志语义），行为不劣化于 v1.4；publish 路径绝不
 * 阻塞任务执行线程。订阅循环内 Redis 异常 → 流优雅结束，客户端经 Last-Event-ID
 * 重连恢复（重连时若 Redis 仍不可达则整体降级内存重放）。
 */
public class ProtocolRedisEventBus implements AgentProtocolEventBus {

    private static final Logger log = LoggerFactory.getLogger(ProtocolRedisEventBus.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 事件流留存（EXPIRE；complete 后缩短到 {@link #COMPLETE_TTL}） */
    static final Duration EVENT_TTL = Duration.ofHours(24);
    /** complete 后的收窄留存（保完成标记可查，重放窗口收窄） */
    static final Duration COMPLETE_TTL = Duration.ofHours(1);
    /** tail 循环 XREAD block 时长（毫秒）：取消感知延迟上界 */
    static final long BLOCK_MS = 1000;
    /** 每次 XREAD 最多取回条数 */
    static final int READ_COUNT = 50;

    /** Redis 门面（null = 纯内存模式，语义等同 SDK 默认实现） */
    private final RedisConnectionFacade facade;
    /** 降级通道：SDK 内存总线（默认 replay 256，与 v1.4 行为一致） */
    private final AgentProtocolTaskEventBus memory = new AgentProtocolTaskEventBus();

    public ProtocolRedisEventBus(RedisConnectionFacade facade) {
        this.facade = facade;
        if (facade == null) {
            log.info("ProtocolRedisEventBus: no redis facade, pure in-memory mode");
        } else {
            log.info("ProtocolRedisEventBus: redis streams enabled (prefix={})",
                facade.key(""));
        }
    }

    // ---------------------------------------------------------------- key 组装

    /** cluster 模式下 taskId 包 hash tag（三键同 slot）；standalone 原样（存量语义） */
    private String tagged(String taskId) {
        return facade.isClusterMode() ? "{" + taskId + "}" : taskId;
    }

    String eventsKey(String taskId) {
        return facade.key("proto:task:" + tagged(taskId) + ":events");
    }

    String seqKey(String taskId) {
        return facade.key("proto:task:" + tagged(taskId) + ":seq");
    }

    String doneKey(String taskId) {
        return facade.key("proto:task:" + tagged(taskId) + ":done");
    }

    // ---------------------------------------------------------------- 总线语义

    @Override
    public RemoteAgentEvent publish(String taskId, RemoteAgentEvent event) {
        if (facade == null) {
            return memory.publish(taskId, event);
        }
        try {
            RedisClusterCommands<String, String> sync = facade.sync();
            Long seq = sync.incr(seqKey(taskId));
            if (seq == null) {
                throw new IllegalStateException("INCR returned null");
            }
            event.setSeq(seq);
            // SDK 契约（内存总线字节码实证）：TaskStore 不设 taskId，由总线补全——
            // 落流 JSON 与跨副本 SSE 事件必须带 taskId
            event.setTaskId(taskId);
            var id = seq + "-0";
            String json = MAPPER.writeValueAsString(event);
            String rejected = sync.xadd(eventsKey(taskId), new XAddArgs().id(id), Map.of(id, json));
            if (rejected == null) {
                // 显式 ID 被拒 = 并发发布竞态（多副本重叠期 seq 乱序）：兜底进内存通道，
                // 让本副本订阅者仍可见（跨副本一致性以快照轮询为准）
                log.warn("[ProtocolEventBus] XADD rejected (concurrent publish) task {} seq {}, "
                    + "fallback to local memory bus", taskId, seq);
                return memory.publish(taskId, event);
            }
            sync.expire(eventsKey(taskId), EVENT_TTL.toSeconds());
            sync.expire(seqKey(taskId), EVENT_TTL.toSeconds());
            return event;
        } catch (Exception e) {
            degraded("publish", taskId, e);
            return memory.publish(taskId, event);
        }
    }

    @Override
    public Flux<RemoteAgentEvent> subscribe(String taskId, long fromSeq) {
        if (facade == null) {
            return memory.subscribe(taskId, fromSeq);
        }
        // 回放探测在 defer 内：Redis 不可达（含降级期）整体切换内存总线——降级期
        // publish 进内存缓冲的事件对本副本订阅者仍然可见（§18.3 fail-soft 语义）。
        // 降级订阅以 fromSeq=0 进入内存总线（宁重复不静默）：内存总线的 seq 是
        // channel 独立计数，与 Redis 发号空间断裂，按 Redis 时代的 fromSeq 过滤
        // 会把降级期事件全部滤掉（CR P1-2）
        return Flux.<RemoteAgentEvent>defer(() -> {
            List<StreamMessage<String, String>> replayed;
            try {
                replayed = facade.sync().xrange(eventsKey(taskId),
                    Range.from(Range.Boundary.including((fromSeq + 1) + "-0"),
                        Range.Boundary.including("+")));
            } catch (Exception e) {
                degraded("subscribe", taskId, e);
                return memory.subscribe(taskId, 0);
            }
            return tail(taskId, replayed, fromSeq);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public void complete(String taskId) {
        // 本进程订阅者（含降级期订阅者）立即完成；跨副本订阅者经 done 标记在下一轮 tail 感知
        memory.complete(taskId);
        if (facade == null) {
            return;
        }
        try {
            RedisClusterCommands<String, String> sync = facade.sync();
            sync.set(doneKey(taskId), "1", io.lettuce.core.SetArgs.Builder.ex(COMPLETE_TTL.toSeconds()));
            sync.expire(eventsKey(taskId), COMPLETE_TTL.toSeconds());
            sync.expire(seqKey(taskId), COMPLETE_TTL.toSeconds());
        } catch (Exception e) {
            degraded("complete", taskId, e);
        }
    }

    // ---------------------------------------------------------------- Redis 订阅流

    /** XRANGE 回放（探测已通过）→ XREAD block tail；终态经 done 标记收流。在 boundedElastic 上跑阻塞循环（绝不占 Reactor 调度线程）。 */
    private Flux<RemoteAgentEvent> tail(String taskId,
                                        List<StreamMessage<String, String>> replayed,
                                        long fromSeq) {
        return Flux.<RemoteAgentEvent>create(sink -> {
            var cancelled = new AtomicBoolean(false);
            sink.onDispose(() -> cancelled.set(true));
            try {
                var ek = eventsKey(taskId);
                // XREAD offset 语义 = 严格大于：无回放帧时从 fromSeq 排他起点续读（不丢帧、不重放）
                String cursor = fromSeq + "-0";
                if (replayed != null) {
                    for (var msg : replayed) {
                        if (cancelled.get()) {
                            sink.complete();
                            return;
                        }
                        emit(sink, taskId, msg);
                        cursor = msg.getId();
                    }
                }
                while (!cancelled.get()) {
                    // 先排水后查 done（CR P1-1）：只在 XREAD 排空时才探测终态——
                    // 「发布最后事件 + complete」与订阅循环并发时，先查 done 会把
                    // 终态帧永久吞掉（客户端不重连就再也收不到）
                    var fresh = facade.sync().xread(
                        XReadArgs.Builder.block(BLOCK_MS).count(READ_COUNT),
                        XReadArgs.StreamOffset.from(ek, cursor));
                    if (fresh != null && !fresh.isEmpty()) {
                        for (var msg : fresh) {
                            emit(sink, taskId, msg);
                            cursor = msg.getId();
                        }
                        continue;
                    }
                    if (isDone(taskId)) {
                        sink.complete();
                        return;
                    }
                }
                sink.complete();
            } catch (Exception e) {
                log.warn("[ProtocolEventBus] subscribe loop ended for task {}: {} (client resumes "
                    + "via Last-Event-ID)", taskId, e.getMessage());
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean isDone(String taskId) {
        try {
            return "1".equals(facade.sync().get(doneKey(taskId)));
        } catch (Exception e) {
            // 探测失败按未完成继续 tail；持续故障由订阅循环异常路径收口
            return false;
        }
    }

    /** 坏帧跳过（反序列化失败不毒化整条流）；body 为单字段 Map（field=id, value=JSON） */
    private void emit(reactor.core.publisher.FluxSink<RemoteAgentEvent> sink,
                      String taskId, StreamMessage<String, String> msg) {
        try {
            var body = msg.getBody();
            if (body == null || body.isEmpty()) {
                return;
            }
            sink.next(MAPPER.readValue(body.values().iterator().next(), RemoteAgentEvent.class));
        } catch (Exception e) {
            log.warn("[ProtocolEventBus] bad frame skipped task {} id {}: {}",
                taskId, msg.getId(), e.getMessage());
        }
    }

    /** 降级日志（限频：同 (op,taskId) 60s 内只 WARN 一次，其余降为 debug——长故障不刷屏） */
    private void degraded(String op, String taskId, Exception e) {
        var key = op + "|" + taskId;
        long now = System.currentTimeMillis();
        var last = lastDegradedLogAt.get(key);
        if (last == null || now - last > 60_000) {
            lastDegradedLogAt.put(key, now);
            log.warn("[ProtocolEventBus] redis {} failed for task {}, degraded to in-memory bus: {}",
                op, taskId, e.getMessage());
        } else {
            log.debug("[ProtocolEventBus] redis {} still failing for task {}: {}", op, taskId, e.getMessage());
        }
    }

    /** 降级日志限频表（key → 上次 WARN 时刻；条目为 task 维度小对象，随进程生命周期） */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastDegradedLogAt =
        new java.util.concurrent.ConcurrentHashMap<>();
}
