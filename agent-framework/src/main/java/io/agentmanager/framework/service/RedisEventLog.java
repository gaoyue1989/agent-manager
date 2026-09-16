package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.config.AgentRedisProperties;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.Limit;

/**
 * session_event 的 Redis Streams 存储（见 docs/durable-sse-multinode-impl-plan.md）。
 *
 * <p>这一层只做「真的要和 Redis 说话」的事：XADD 管道刷出、XRANGE 读、ZADD NX 维护
 * reply 索引、EXPIRE/TTL、DEL。攒批、seq 分配、游标分页、replyId 过滤的**语义**全在
 * {@link SessionEventStore}，因此那一层的测试可以用 mock 覆盖，只有本类需要真 Redis。
 *
 * <h2>数据模型</h2>
 * <pre>
 *   sess:{sid}:events    Stream  每条 = 一个事件，ID = {@code <seq>-0}，字段 t/r/p
 *   sess:{sid}:replies   ZSET    member = replyId, score = 该 reply 的首个 seq（ZADD NX）
 * </pre>
 *
 * <p><b>没有 {@code sess:{sid}:seq} 这个 key。</b>seq 计数器由**流顶端**推导
 * （{@link #tailSeq} 走 XREVRANGE COUNT 1），因此流是唯一事实来源，计数器不可能跑到
 * 读者看得见的数据前面去，也不存在「计数器 key 因 TTL 先过期、而流还在 ⇒ 重新从 1 发号
 * ⇒ 之后每一条 XADD 都被拒 ⇒ 回放永久损坏」这条最难查的路径。
 *
 * <h2>为什么 ID 是 {@code <seq>-0} 而不是时间戳</h2>
 * 客户端游标就是 {@code seq}（debug 页对 SSE {@code id:} 做 parseInt 取最大值、
 * {@code chat.js} 读 {@code status.latest_event_seq}、{@code @RequestParam Integer afterSeq}），
 * ID 直接用 seq 就让游标查询变成**精确的 O(log N)**，不需要任何二级索引。
 * 代价是 ID 不再是时间戳 ⇒ 不能用 {@code XTRIM MINID <时间>} 做留存，改用 TTL。
 *
 * <p><b>硬约束：绝不要在这些 key 上用 {@code XADD key *}（自动 ID）。</b>自动 ID 是
 * {@code <服务器毫秒>-<n>}（~1.7e12），会把流顶端一下子顶到未来，之后所有
 * {@code <seq>-0} 的 XADD 全部被拒。同理不要用 {@code XSETID}。
 *
 * <h2>单写者兜底</h2>
 * {@code XADD} 一条 ID ≤ 流顶端的事件会被 Redis **原子地拒绝**
 * （{@code ERR The ID specified in XADD is equal or smaller than the target stream top item}）。
 * 这比原来的 {@code uk_session_seq} 唯一键更强：唯一键只保证「不重复」，它还顺带保证了
 * **单调递增**。关系型那层兜底没有丢失，只是搬进了流里。
 *
 * <p><b>失败语义</b>：写路径（{@link #appendBatch}）不抛，返回 -1，与旧的
 * {@code insertBatch} 一致——持久化失败不该阻塞主链路。读路径**抛**，由
 * {@link SessionEventStore} 决定哪里宽容（轮询）哪里严格（/status 要 503）。
 */
public class RedisEventLog {

    private static final Logger log = LoggerFactory.getLogger(RedisEventLog.class);

    /** 字段名：t=type, r=replyId, p=payload。短名 + 固定顺序，让 listpack 能压缩重复字段名。 */
    static final String FIELD_TYPE = "t";
    static final String FIELD_REPLY = "r";
    static final String FIELD_PAYLOAD = "p";

    /** Redis 对「ID 不大于流顶端」的报错原文（用于把「丢租约」与「容量问题」分开） */
    private static final String ERR_NON_INCREASING_ID =
        "equal or smaller than the target stream top item";

    /** 到顶时的报错原文（noeviction 下的写入失败） */
    private static final String ERR_OOM = "OOM command not allowed";

    /**
     * 建连失败后的退避窗口（毫秒）。Redis 不可达时，若每次操作都去 connect，
     * 每次都会阻塞到 connectTimeout（默认 2s）——等于把 Redis 的故障放大成
     * 「每条 append 卡 2 秒」。窗口内的调用立刻抛上一次的异常，不再尝试建连。
     */
    private static final long CONNECT_BACKOFF_MS = 1000;

    /** 带序号的一条事件（ID 由 seq 决定） */
    public record Entry(int seq, String type, String replyId, String payload) {}

    /** 读回来的一条事件 */
    public record Event(int seq, String type, String payload, String replyId) {}

    /** 一个 reply 的首个 seq（ZSET 索引项） */
    public record ReplyBoundary(String replyId, int firstSeq) {}

    private final RedisClient client;
    private final int maxLenPerStream;
    private final long commandTimeoutMs;

    /** 惰性连接：启动时不建连，所以 Redis 可达与否不影响服务启动 */
    private volatile StatefulRedisConnection<String, String> conn;

    /** 自检只跑一次（成功才置位，见 {@link #selfCheck}） */
    private final AtomicBoolean selfChecked = new AtomicBoolean(false);

    /** 上一次建连失败的原因（退避窗口内直接复用它抛出，避免造 Lettuce 异常对象） */
    private final AtomicReference<RedisException> lastConnectFailure = new AtomicReference<>();
    private volatile long lastConnectFailureAt = 0L;

    public RedisEventLog(RedisClient client, AgentRedisProperties props) {
        this.client = client;
        this.maxLenPerStream = props.maxLenPerStream();
        this.commandTimeoutMs = props.commandTimeoutMs();
    }

    static String eventsKey(String sessionId) {
        return "sess:" + sessionId + ":events";
    }

    static String repliesKey(String sessionId) {
        return "sess:" + sessionId + ":replies";
    }

    // ---------------------------------------------------------------- 连接

    /**
     * 取连接，必要时建连（并在建连成功后跑一次自检）。
     *
     * <p>{@code ClientOptions.disconnectedBehavior(REJECT_COMMANDS)} 保证**已建立**的连接
     * 在断线期间立刻让命令失败，而不是缓冲到重连成功。
     */
    private StatefulRedisConnection<String, String> connection() {
        var c = conn;
        if (c != null && c.isOpen()) {
            return c;
        }
        synchronized (this) {
            if (conn != null && conn.isOpen()) {
                return conn;
            }
            var failure = lastConnectFailure.get();
            if (failure != null
                    && System.currentTimeMillis() - lastConnectFailureAt < CONNECT_BACKOFF_MS) {
                throw failure;   // 退避窗口内：立刻失败，不阻塞调用线程
            }
            try {
                var fresh = client.connect();
                conn = fresh;
                lastConnectFailure.set(null);
                selfCheck(fresh);
                return fresh;
            } catch (RedisException e) {
                lastConnectFailure.set(e);
                lastConnectFailureAt = System.currentTimeMillis();
                throw e;   // RedisException 是非受检异常，调用方无需声明
            }
        }
    }

    /**
     * 启动自检：{@code appendonly} 与 {@code maxmemory-policy} 是否合规。
     *
     * <p><b>只告警，绝不 abort。</b>两者都是客户端设不了的运维面配置，与仓库既有的
     * 「响亮跳过」取向一致：说清后果，交人工处置。
     *
     * <p>放在**首次连接成功之后**而不是构造期：构造期连不上就没得查，且会让启动
     * 卡一个 connectTimeout。这样只有真能跟 Redis 说话时才自检，报错也才可信。
     *
     * <p>失败时**不置位** {@code selfChecked}，留给下一次调用重试；成功才置位——
     * 否则一次瞬时抖动就永久失去这次自检。
     */
    private void selfCheck(StatefulRedisConnection<String, String> c) {
        if (selfChecked.get()) {
            return;
        }
        try {
            var sync = c.sync();
            var appendonly = sync.configGet("appendonly").get("appendonly");
            var policy = sync.configGet("maxmemory-policy").get("maxmemory-policy");
            // 三条都以「RedisEventLog: 持久性自检」开头：运维可直接 grep 这一个前缀拿到结论，
            // 不必逐条读消息体（也让 IT 能断言「自检确实跑过」，而不只是「没报某个错」）
            if (!"yes".equalsIgnoreCase(appendonly)) {
                log.error("RedisEventLog: 持久性自检**未通过**——appendonly={}，未开 AOF，"
                    + "会话事件的「持久回放」承诺不成立：进程/实例崩溃会丢掉最后一次快照之后的"
                    + "**全部**事件，而客户端无法察觉这个空洞。请设 appendonly yes"
                    + "（manifest 里已是 --appendonly yes）。", appendonly);
            }
            if (!"noeviction".equalsIgnoreCase(policy)) {
                log.error("RedisEventLog: 持久性自检**未通过**——maxmemory-policy={}，"
                    + "到内存上限时会**静默淘汰**会话事件，表现为断线回放莫名少一段（比写入失败更难查）。"
                    + "请设 noeviction；到顶时应让写入响亮失败（append 返回 -1），而不是悄悄丢数据。",
                    policy);
            }
            if ("yes".equalsIgnoreCase(appendonly) && "noeviction".equalsIgnoreCase(policy)) {
                log.info("RedisEventLog: 持久性自检通过——appendonly=yes, maxmemory-policy=noeviction");
            }
            selfChecked.set(true);
        } catch (Exception e) {
            // CONFIG GET 可能被托管 Redis 禁用；自检失败不该影响任何功能
            log.warn("RedisEventLog: 持久性自检跳过（{}）", e.toString());
        }
    }

    // ---------------------------------------------------------------- 写

    /**
     * 一条管道刷出一批事件。返回写入条数；**< 0 表示失败**（与旧的 {@code insertBatch} 一致）。
     *
     * <p><b>命令顺序是有讲究的，不要重排</b>（管道不是事务，进程可能在任意位置断掉）：
     * <pre>
     *   ZADD NX (× 本批不同的 replyId) → EXPIRE replies → XADD (× 每条) → EXPIRE events → EXPIRE replies
     * </pre>
     * 「索引先于数据」使截断的管道只会留下**有索引没数据**（无害：回放少一段本就没写进去的内容），
     * 而不会留下**有数据没索引**（有害：带 replyId 的回放与 history 会静默返回空）。
     * 前后各一次 {@code EXPIRE replies}：第一次在 key 已存在时续期，第二次覆盖「本批刚由 ZADD
     * 创建」的情形（key 刚创建时没有 TTL，只能靠后面这次补上）。
     *
     * <p><b>不用 MULTI/EXEC</b>：{@code MULTI} 是连接级状态，多个 agent 线程共用一条
     * Lettuce 连接时并发下发 MULTI 会真的互相穿插。
     *
     * <p><b>部分失败是可能的</b>：Redis 没有回滚，所以返回 -1 时本批**可能有一部分已经写进去**。
     * 这比旧实现（一条多值 INSERT，失败则一行不落）丢得少，且回放本来就容忍空洞
     * （按 {@code seq > cursor} 读，不依赖连续性）。
     */
    public int appendBatch(String sessionId, List<Entry> entries, int ttlSeconds) {
        if (entries.isEmpty()) {
            return 0;
        }
        List<RedisFuture<?>> futures = new ArrayList<>(entries.size() + 4);
        try {
            var async = connection().async();
            var events = eventsKey(sessionId);
            var replies = repliesKey(sessionId);

            // 1) reply 索引：每个**新出现的** replyId 一条 ZADD NX。
            //    NX 让重复写入变成 no-op 且**保留首个 score**（已实测：先 5 后 9，结果仍是 5），
            //    所以这里不需要先读索引判断「是否已存在」——省一次往返。
            for (var b : firstSeqPerReply(entries).entrySet()) {
                futures.add(async.zadd(replies, ZAddArgs.Builder.nx(),
                    b.getValue().doubleValue(), b.getKey()));
            }
            futures.add(async.expire(replies, ttlSeconds));

            // 2) 数据
            for (var e : entries) {
                var body = new LinkedHashMap<String, String>(4);
                body.put(FIELD_TYPE, e.type());
                // replyId 用空串占位而不是省略字段：省略会让「字段 → 值」的 listpack
                // 压缩在有无 replyId 的条目之间错位，且读取侧要多一个判空分支
                body.put(FIELD_REPLY, e.replyId() != null ? e.replyId() : "");
                body.put(FIELD_PAYLOAD, e.payload());
                var args = new XAddArgs()
                    .id(e.seq() + "-0")
                    .maxlen(maxLenPerStream)
                    .approximateTrimming(true);
                futures.add(async.xadd(events, args, body));
            }

            // 3) TTL：events 与 replies 同生共死（两者续期点相同，故不会互相错位过期）
            futures.add(async.expire(events, ttlSeconds));
            futures.add(async.expire(replies, ttlSeconds));

            async.flushCommands();
            return awaitAll(futures, entries, sessionId);
        } catch (Exception e) {
            return fail(entries, sessionId, e);
        }
    }

    /** 本批中每个 replyId 的**最小** seq（即该 reply 的首个 seq）。保持插入顺序以便日志可读。 */
    private static Map<String, Integer> firstSeqPerReply(List<Entry> entries) {
        var out = new LinkedHashMap<String, Integer>();
        for (var e : entries) {
            var rid = e.replyId();
            if (rid != null && !rid.isBlank()) {
                out.merge(rid, e.seq(), Math::min);
            }
        }
        return out;
    }

    /**
     * 等待全部命令完成。
     *
     * <p>外层再套一个兜底时限（命令超时的 2 倍 + 1s）：正常情况下 Lettuce 的
     * {@code TimeoutOptions} 会先把单条命令判超时，这里的时限只是防止某个路径漏掉超时处理
     * 而把 agent 线程永久挂住。
     */
    private int awaitAll(List<RedisFuture<?>> futures, List<Entry> entries, String sessionId) {
        long boundMs = commandTimeoutMs * 2L + 1000L;
        long deadline = System.currentTimeMillis() + boundMs;
        for (var f : futures) {
            try {
                long remain = deadline - System.currentTimeMillis();
                f.get(Math.max(1L, remain), TimeUnit.MILLISECONDS);
            } catch (ExecutionException ee) {
                return fail(entries, sessionId, ee.getCause() != null ? ee.getCause() : ee);
            } catch (TimeoutException te) {
                log.error("RedisEventLog: 刷出超时——session {} 本批 {} 行（seq {}..{}）"
                    + "在 {}ms 内未确认完成，本批可能只写进去一部分。"
                    + "单条命令超时（{}ms）本应先触发，走到这里说明有路径漏了超时处理。",
                    sessionId, entries.size(),
                    entries.get(0).seq(), entries.get(entries.size() - 1).seq(),
                    boundMs, commandTimeoutMs);
                return -1;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("RedisEventLog: 刷出被中断——session {} 本批 {} 行（seq {}..{}）未确认",
                    sessionId, entries.size(),
                    entries.get(0).seq(), entries.get(entries.size() - 1).seq());
                return -1;
            }
        }
        return entries.size();
    }

    /**
     * 刷出失败的分类与日志。三类原因的**处置完全不同**，所以必须分开说，不能都报一句
     * 「写入失败」：先给证据（批的 seq 区间、行数），再给假设。
     */
    private int fail(List<Entry> entries, String sessionId, Throwable cause) {
        var msg = cause != null ? cause.getMessage() : null;
        int firstSeq = entries.get(0).seq();
        int lastSeq = entries.get(entries.size() - 1).seq();
        if (msg != null && msg.contains(ERR_NON_INCREASING_ID)) {
            // 证据在前，假设在后——与 SessionEventStore 里那条唯一键冲突日志同一取向：
            // 只给结论会把排查带偏。
            log.error("RedisEventLog: XADD 被拒——session {} 本批 {} 行（seq {}..{}，批内不同 seq 数 {}）。"
                + "Redis 拒绝了 ID ≤ 流顶端的事件，说明本副本分配的 seq 区间与流中已有事件重叠。"
                + "两种可能：(a) 另一副本已写入更高的 seq（本副本丢了 turn_lease 却仍在写，I2 被违反），"
                + "此时请核对 turn 租约；(b) 本副本的 seq 计数器偏低（播种失败或旧计数器未清）。"
                + "若「批内不同 seq 数」为 1 且行数 > 1，则是 (b)——整批撞在同一个 seq 上，与租约无关。",
                sessionId, entries.size(), firstSeq, lastSeq,
                entries.stream().mapToInt(Entry::seq).distinct().count(), cause);
        } else if (msg != null && msg.contains(ERR_OOM)) {
            log.error("RedisEventLog: 写入被拒——**Redis 内存已达 maxmemory**（noeviction）——session {} "
                + "本批 {} 行（seq {}..{}）未写入，这段回放会缺。"
                + "这是容量问题，不是租约问题。请提高 maxmemory 或调小 agent.redis.max-len-per-stream。"
                + "注意读路径不受影响（回放/status 仍可用），且 TTL 仍会照常过期、内存释放后自行恢复。",
                sessionId, entries.size(), firstSeq, lastSeq, cause);
        } else if (cause instanceof RedisConnectionException
                || cause instanceof RedisCommandTimeoutException) {
            // 可自愈的一类：Redis 抖动/不可达。单条 WARN、不带堆栈——它会随每次 append 重复，
            // 而这类故障的排查入口是 Redis 自身的健康度，不是这份堆栈。
            log.warn("RedisEventLog: 刷出失败（Redis 不可达或超时）——session {} 本批 {} 行（seq {}..{}）未写入：{}",
                sessionId, entries.size(), firstSeq, lastSeq,
                cause != null ? cause.toString() : "null");
        } else {
            log.error("RedisEventLog: 刷出失败——session {} 本批 {} 行（seq {}..{}）：{}",
                sessionId, entries.size(), firstSeq, lastSeq,
                cause != null ? cause.toString() : "null", cause);
        }
        return -1;
    }

    // ---------------------------------------------------------------- 读

    /**
     * 按 seq 区间读事件（升序）。
     *
     * <p>游标用的是**闭区间**：调用方传 {@code fromSeq = afterSeq + 1}。不用
     * {@code XRANGE key (cursor} 那种排他前缀写法——它要求 Redis ≥ 6.2，且
     * {@code afterSeq = -1} 时会拼出不存在的 ID {@code -1-0} 直接报错。
     *
     * @param toSeqInclusive 上界（含）；{@code null} 表示不限
     * @param limit          最多返回条数
     */
    public List<Event> range(String sessionId, int fromSeq, Integer toSeqInclusive, int limit) {
        var upper = toSeqInclusive != null ? toSeqInclusive + "-0" : "+";
        var msgs = connection().sync().xrange(
            eventsKey(sessionId),
            Range.create(fromSeq + "-0", upper),
            Limit.from(limit));
        var out = new ArrayList<Event>(msgs.size());
        for (var m : msgs) {
            out.add(toEvent(m));
        }
        return out;
    }

    /** 最新一条事件；无事件时返回 null。 */
    public Event latest(String sessionId) {
        // Range.unbounded() 是 XREVRANGE 的正确写法。
        // **不要**写成 Range.create("+", "-")——实测返回空列表（不是报错，是静默返回空），
        // 而这个静默空值会让 tailSeq 恒为 0、seq 从 1 重发、之后每条 XADD 全被拒。
        var msgs = connection().sync().xrevrange(
            eventsKey(sessionId), Range.unbounded(), Limit.from(1));
        return msgs.isEmpty() ? null : toEvent(msgs.get(0));
    }

    /**
     * 流顶端的 seq；流不存在或为空时返回 0。
     *
     * <p>这是 seq 计数器的**唯一**播种来源（取代原来的 {@code SELECT MAX(seq)}）。
     * XREVRANGE 从尾部反向迭代，是 O(1) 量级，不会随流长度增长。
     */
    public int tailSeq(String sessionId) {
        var latest = latest(sessionId);
        return latest != null ? latest.seq() : 0;
    }

    /**
     * 该 session 出现过的 replyId 及其首个 seq，按首个 seq 升序。
     *
     * <p>ZRANGE 按 score 升序返回，等价于原来的
     * {@code GROUP BY reply_id ORDER BY MIN(seq)}。
     */
    public List<ReplyBoundary> replies(String sessionId) {
        var scored = connection().sync().zrangeWithScores(repliesKey(sessionId), 0, -1);
        var out = new ArrayList<ReplyBoundary>(scored.size());
        for (var sv : scored) {
            out.add(new ReplyBoundary(sv.getValue(), (int) sv.getScore()));
        }
        return out;
    }

    /** 删除该 session 的全部 key（级联删除用）。返回删除的 key 数。 */
    public int deleteSession(String sessionId) {
        var n = connection().sync().del(eventsKey(sessionId), repliesKey(sessionId));
        return n != null ? n.intValue() : 0;
    }

    /**
     * 把一条 StreamMessage 转成 {@link Event}。
     *
     * <p>{@code seq} 从 ID 的 {@code <seq>-0} 里解析，**不从字段里读**——ID 才是事实来源，
     * 存一份字段反而多一个可能不一致的地方。
     */
    private static Event toEvent(io.lettuce.core.StreamMessage<String, String> m) {
        var body = m.getBody();
        var reply = body.get(FIELD_REPLY);
        return new Event(
            parseSeq(m.getId()),
            body.get(FIELD_TYPE),
            body.get(FIELD_PAYLOAD),
            reply == null || reply.isEmpty() ? null : reply);
    }

    /** 解析 Stream ID 的 seq 部分（{@code "123-0"} → {@code 123}） */
    static int parseSeq(String id) {
        int dash = id.indexOf('-');
        var head = dash >= 0 ? id.substring(0, dash) : id;
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            // 只会发生在我们自己用了自动 ID / XSETID 的情况下（见类 javadoc 的硬约束）
            throw new IllegalStateException(
                "RedisEventLog: stream ID 不是 <seq>-0 形态（" + id + "）——"
                    + "该 key 上不应出现 XADD * 或 XSETID 写入的条目", e);
        }
    }
}
