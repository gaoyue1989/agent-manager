package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;

/**
 * 事件持久化存储（Redis Streams，见 docs/api-frontend-sse.md §12）。
 *
 * <p>将 agent 执行过程中的 SSE 事件持久化，供前端断连后通过
 * {@code GET /threads/{sid}/subscribe?afterSeq=N} 回放增量事件并续传。
 *
 * <p>存储内容复用 {@link AgentEventSseSerializer#payload} 的 JSON 输出，保证回放词表与
 * 实时推送完全一致，前端渲染逻辑零改动。
 *
 * <p>本类只保留与存储引擎**无关**的语义：按 session 分区的攒批、seq 分配、游标分页、
 * replyId 过滤、turn 收尾。真正与 Redis 对话的部分在 {@link RedisEventLog}——因此这里
 * 的测试全部可以 mock，只有那一层需要真 Redis（见 {@code RedisEventLogIT}）。
 *
 * <h2>与 MySQL 版的差异</h2>
 * <ul>
 *   <li>seq 计数器的播种来源从 {@code SELECT MAX(seq)} 换成**流顶端**
 *       （{@link RedisEventLog#tailSeq}），并额外取「本 session 缓冲中已分配的最大 seq」
 *       作为下界（见 {@link #seedFloorIfLower}）。</li>
 *   <li>单写者兜底从 {@code uk_session_seq} 唯一键换成 Redis 对 XADD 非单调 ID 的
 *       原子拒绝——更强（同时保证唯一与单调），且不需要整批回滚。</li>
 *   <li>留存从 03:00 的 {@code DELETE ... WHERE created_at < ?} 换成 key TTL。</li>
 * </ul>
 */
public class SessionEventStore {

    private static final Logger log = LoggerFactory.getLogger(SessionEventStore.class);

    /** delta 类事件的后缀：进缓冲攒批，不逐条落库 */
    private static final String DELTA_SUFFIX = "_DELTA";

    /**
     * 是否是流式增量事件（进缓冲攒批）。
     *
     * <p><b>按后缀判定，不维护白名单。</b>事件词表由外部依赖 `io.agentscope.core.event.AgentEventType`
     * 拥有，且它会长：`*_DELTA` 共 6 种（TEXT/THINKING/DATA_BLOCK、TOOL_CALL、TOOL_RESULT_TEXT/DATA）。
     * 原先硬编码只列了 2 种，于是另外 4 种落进「里程碑」分支、**每一条都立刻刷一次批**——实测
     * 88,445 条 `TOOL_CALL_DELTA` 就是 88,445 条 INSERT，写放大 26 倍，而这是**词表漂移**造成的
     * 静默退化，不是有人做了错误决定。按后缀判定后，依赖再加 delta 类型也不会退化。
     *
     * <p>误判的代价是不对称的：把里程碑当 delta 只会让回放的可见延迟 ≤ 一个刷出窗口
     * （实时流不受影响——`emit` 是先 append 再推 sink）；把 delta 当里程碑则是每 token 一条写。
     * 所以这里宁可放宽。
     */
    private static boolean isDelta(String eventType) {
        return eventType != null && eventType.endsWith(DELTA_SUFFIX);
    }

    /** 默认批量大小（行数） */
    private static final int DEFAULT_BATCH_SIZE = 200;

    /** 默认攒批时间窗（毫秒）：超过则把缓冲刷出，限制重连回放的滞后 */
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 1000;

    /** 回放分页大小：限制单次查询 materialize 的行数 */
    private static final int QUERY_PAGE_SIZE = 500;

    /** 秒/天 */
    private static final long SECONDS_PER_DAY = 86_400L;

    /** 回放分页大小（测试可见） */
    static int queryPageSize() {
        return QUERY_PAGE_SIZE;
    }

    private final RedisEventLog eventLog;
    private final int retentionDays;
    private final int batchSize;
    private final int flushIntervalMs;

    /**
     * session_id → 下一个待分配的 seq。
     *
     * <p>seq 是 session 维度递增的，而 turn 会跨副本交接（HITL 恢复产生新 turn、
     * 新 replyId，可能在另一个 Pod 上执行）。因此计数器必须在**每个 turn 开始时**
     * 从流顶端续起，不能只在 Pod 启动时初始化一次。
     *
     * <p>安全性依赖：同一 session 的 turn 由 turn_lease 全局串行化，任一时刻只有一个
     * Pod 在写该 session。若该前提被放松，本计数器会产生 seq 冲突——而 Redis 会以
     * 「XADD 非单调 ID 被拒」把它变响亮（见 {@link #flushBatch}）。
     */
    private final ConcurrentHashMap<String, AtomicInteger> seqCounters = new ConcurrentHashMap<>();

    /** 一个 session 的待落库缓冲 */
    private static final class Buffer {
        final List<PendingRow> rows = new ArrayList<>();
        long lastFlushAt = System.currentTimeMillis();
        /** 本缓冲里已分配过的最大 seq（播种下界用，见 {@link #seedFloorIfLower}） */
        int maxSeq;
    }

    /**
     * session_id → 待落库缓冲；访问一律持 {@code pending} 监视器。
     *
     * <p><b>按 session 分区</b>，而不是一条全局 FIFO：这样一个 session 的刷出失败
     * （XADD 被拒 / 容量上限）不会连带丢掉邻居的行，{@code finishTurn(A)} 也只刷 A 的行。
     */
    private final Map<String, Buffer> pending = new LinkedHashMap<>();

    /** 攒批缓冲中的一行（尚未落库） */
    private record PendingRow(int seq, String replyId, String eventType, String payload) {}

    public SessionEventStore(RedisEventLog eventLog) {
        this(eventLog, 7, DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public SessionEventStore(RedisEventLog eventLog, int retentionDays) {
        this(eventLog, retentionDays, DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public SessionEventStore(RedisEventLog eventLog, int retentionDays,
                             int batchSize, int flushIntervalMs) {
        this.eventLog = eventLog;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.flushIntervalMs = flushIntervalMs >= 0 ? flushIntervalMs : DEFAULT_FLUSH_INTERVAL_MS;
    }

    /** TTL（秒）：留存现在由 Redis key TTL 承担 */
    long ttlSeconds() {
        return Math.max(1L, retentionDays * SECONDS_PER_DAY);
    }

    // ------------------------------------------------------------ seq 分配

    /**
     * 播种/校正 seq 计数器（turn 开始时调用，必须在本 Pod 获得 turn_lease 之后）。
     *
     * <p>取值是 {@code max(现有计数器, 流顶端, 本 session 缓冲中已分配的最大 seq)}——
     * <b>只会抬高，绝不降低</b>。只抬高这一点是必须的：计数器里可能已经有「已发给调用方、
     * 但还没落库」的 seq，把计数器往回拨会让那些 seq 被重新发一次。
     *
     * <p>为什么要把**缓冲**也算进下界：流顶端看不见尚未刷出的行，而缓冲里的行已经拿到
     * 了 seq（可能已推给客户端）。只看流顶端的话，「计数器和缓冲都被清掉后又来了一行」
     * 就会从流顶端重新发号，撞上已发出去的 seq——这正是线上那次
     * 「本批 200 行 seq 5153..5153 整批丢弃」的成因。把缓冲算进来之后，这条路径从
     * 「靠 releaseSeqIfIdle 别在缓冲非空时释放」变成了「即使释放了也安全」。
     */
    public void seedSeq(String sessionId) {
        seedFloorIfLower(sessionId);
    }

    /**
     * 释放 seq 计数器（底层原语，仅供 {@link #releaseSeqIfIdle} 与测试使用）。
     *
     * <p>与 MySQL 版不同，现在释放是**安全**的：重新播种时会取「流顶端 ∪ 缓冲内最大 seq」
     * 作为下界，不会发重号。保留 {@link #releaseSeqIfIdle} 是为了少做无谓的重播种，
     * 不再承担正确性。
     */
    public void releaseSeq(String sessionId) {
        seqCounters.remove(sessionId);
    }

    /**
     * 收尾时释放 seq 计数器——仅当该 session 没有待落库的行。
     *
     * <p>现在这层保护是**保守**而非必需（见 {@link #releaseSeq}）：缓冲非空时重播种也不会
     * 发重号，只是要多一次 XREVRANGE。留着它是因为「收尾就释放」是调用方的意图，
     * 而缓冲非空说明收尾与尾部事件并发，此时保留计数器语义更贴合。
     */
    private void releaseSeqIfIdle(String sessionId) {
        synchronized (pending) {
            if (pending.containsKey(sessionId)) {
                log.debug("SessionEventStore: seq counter kept for {} — 仍有待落库的行", sessionId);
                return;
            }
            releaseSeq(sessionId);
        }
    }

    /** 取该 session 的计数器，不存在则按 {@link #seedFloorIfLower} 播种。 */
    private AtomicInteger counterFor(String sessionId) {
        var existing = seqCounters.get(sessionId);
        if (existing != null) {
            return existing;
        }
        return seqCounters.computeIfAbsent(sessionId, sid -> {
            int floor = seedFloor(sid);
            log.debug("SessionEventStore: seeded seq counter for {} at {}", sid, floor);
            return new AtomicInteger(floor);
        });
    }

    /** 播种下界 = max(流顶端, 本 session 缓冲中已分配的最大 seq) */
    private int seedFloor(String sessionId) {
        int floor = eventLog.tailSeq(sessionId);
        synchronized (pending) {
            var buf = pending.get(sessionId);
            if (buf != null && buf.maxSeq > floor) {
                floor = buf.maxSeq;
            }
        }
        return floor;
    }

    /** 把计数器抬到 {@link #seedFloor}（若它更低的话）。幂等，只抬不降。 */
    private void seedFloorIfLower(String sessionId) {
        int floor = seedFloor(sessionId);
        seqCounters.compute(sessionId, (sid, cur) -> {
            if (cur == null) {
                return new AtomicInteger(floor);
            }
            // CAS 循环：并发 append 可能正在 incrementAndGet，直接 set 会把它推掉的号吃掉
            while (true) {
                int now = cur.get();
                if (now >= floor) {
                    return cur;
                }
                if (cur.compareAndSet(now, floor)) {
                    log.debug("SessionEventStore: seq counter for {} raised {} → {}（流顶端/缓冲已更高）",
                        sid, now, floor);
                    return cur;
                }
            }
        });
    }

    /**
     * 取下一个 seq：**一律**走内存计数器，计数器不存在时在其上惰性播种。
     *
     * <p>这里不能退化成「每次 append 重新读一次流顶端 + 1」：缓冲区里尚未刷出的行对
     * 流顶端是**不可见**的，于是同一个未播种 session 的后续每一行都会拿到同一个 seq，
     * 攒够一批后整批撞在同一个 ID 上被 Redis 拒绝——
     * {@code ERR The ID specified in XADD is equal or smaller than the target stream top item}。
     */
    private int nextSeq(String sessionId) {
        return counterFor(sessionId).incrementAndGet();
    }

    // ------------------------------------------------------------ 写

    /**
     * 追加一条事件记录，返回分配的 seq。
     *
     * <p>delta 类事件（任意 {@code *_DELTA}）进缓冲攒批；里程碑事件触发一次 flush，
     * 把缓冲与自身放在**同一条管道**中刷出。
     *
     * <p>不变量 I1（已刷出的 seq 构成连续前缀）在**每批都刷出成功**时成立：里程碑与它
     * 之前的 delta 同批刷出，不会出现「里程碑已落库但中间的 delta 缺失」。
     *
     * <p>已知偏差：某批刷出失败时该批的行被整体丢弃，且 seq 计数器**不回退**，
     * 随后成功的批次会从更高的 seq 继续，流里因此留下空洞（1,2,3 成功 → 4,5 失败 →
     * 下一批 6,7，落库序列 [1,2,3,6,7]）。不回退是有意的：可能有并发 append 已推进
     * 计数器，回退会直接造成 seq 冲突。空洞不影响续传——回放按 {@code seq > cursor}
     * 读取，不依赖连续性。分区的价值在于：这次丢弃**只波及本 session**。
     *
     * @return 分配的 seq（从 1 开始递增）；-1 表示刷出失败
     */
    public int append(String sessionId, String replyId, String eventType, String payload) {
        int seq = nextSeq(sessionId);
        var row = new PendingRow(seq, replyId, eventType, payload);

        List<PendingRow> toFlush = null;
        synchronized (pending) {
            var buf = pending.computeIfAbsent(sessionId, k -> new Buffer());
            buf.rows.add(row);
            if (seq > buf.maxSeq) {
                buf.maxSeq = seq;
            }
            long now = System.currentTimeMillis();
            boolean milestone = !isDelta(eventType);
            // 三个条件都只按**本 session** 的行数/时间窗评估：别的 session 攒了多少与
            // 本 session 的回放滞后无关，不该由它触发刷出
            boolean full = buf.rows.size() >= batchSize;
            boolean timedOut = now - buf.lastFlushAt >= flushIntervalMs;
            if (milestone || full || timedOut) {
                toFlush = new ArrayList<>(buf.rows);
                buf.rows.clear();
                buf.maxSeq = 0;
                buf.lastFlushAt = now;
            }
        }

        if (toFlush != null && flushBatch(sessionId, toFlush) < 0) {
            return -1;
        }
        return seq;
    }

    /**
     * turn 结束：把**本 session** 的缓冲刷出并释放 seq 计数器。
     * 必须由 turn 的终态路径调用，否则尾部 delta 会一直留在内存中直到下一个 turn。
     *
     * <p>只刷本 session：其他 session 的缓冲要么属于别的 turn（由它自己的终态路径收），
     * 要么属于尚未开始的 turn——都不该被这里的 flush 捎带改变落库时机。
     */
    public void finishTurn(String sessionId) {
        List<PendingRow> toFlush = List.of();
        synchronized (pending) {
            var buf = pending.remove(sessionId);
            if (buf != null && !buf.rows.isEmpty()) {
                toFlush = new ArrayList<>(buf.rows);
            }
        }
        flushBatch(sessionId, toFlush);
        releaseSeqIfIdle(sessionId);
    }

    /**
     * turn 因**租约丢失**而终止：丢弃该 session 的待落库行（不刷出）并释放 seq 计数器。
     *
     * <p>与 {@link #finishTurn} 的唯一差别就是不刷出。缓冲里那些行的 seq 是本副本
     * 「以为自己还持锁」时分配的，而此刻新 owner 可能已在同一区间分配过 seq。把它们
     * 写下去正是丢租约要防的事：在 Redis 侧会表现为整批 XADD 被拒（响亮），
     * 若换成 allkeys-lru 之类允许覆盖的配置则可能静默写重复。
     *
     * <p><b>注意：租约丢失后 seq 不再会被「重号」——但丢弃仍然必要</b>，理由换了：
     * 被丢弃的行属于一个已被取代的 turn，其内容已经过时。若新 owner 尚未写入，这些
     * 序号更低的行会被流**接受**（它们单调递增），于是把作废内容插进实时流。
     *
     * <p>这是**有损**的：客户端看到的事件流会少一段尾巴。但那段尾巴本来就不该属于
     * 本次 turn 的内容——被接管的副本会把完整内容重新产出。
     */
    public void abandonTurn(String sessionId) {
        int dropped = 0;
        synchronized (pending) {
            var buf = pending.remove(sessionId);
            if (buf != null) {
                dropped = buf.rows.size();
            }
        }
        releaseSeqIfIdle(sessionId);
        if (dropped > 0) {
            log.warn("SessionEventStore: turn abandoned (lease lost, sid={}) — "
                + "dropped {} buffered row(s) without writing", sessionId, dropped);
        }
    }

    /**
     * 一批事件走一条 Redis 管道刷出。
     *
     * @return 写入条数；&lt; 0 表示失败（{@link RedisEventLog#appendBatch} 已分类并打过日志）
     */
    private int flushBatch(String sessionId, List<PendingRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        var entries = new ArrayList<RedisEventLog.Entry>(rows.size());
        for (var r : rows) {
            entries.add(new RedisEventLog.Entry(r.seq(), r.eventType(), r.replyId(), r.payload()));
        }
        return eventLog.appendBatch(sessionId, entries, (int) ttlSeconds());
    }

    // ------------------------------------------------------------ 读

    /**
     * 查询 afterSeq 之后的事件（回放用）。
     *
     * <p>分页读取：每页 materialize 成 List 后再从内存中向外发，避免慢客户端反压时
     * 把一次查询钉在整个推送期间。
     *
     * @param afterSeq 游标：返回 seq &gt; afterSeq 的记录；&lt; 0 表示从头
     * @param replyId  turn 标识（可选）；null 表示不限 turn
     */
    public Flux<EnvelopedEvent> queryAfter(String sessionId, String replyId, int afterSeq) {
        return Flux.create(sink -> {
            int cursor = afterSeq;
            try {
                while (!sink.isCancelled()) {
                    var page = queryPage(sessionId, replyId, cursor, QUERY_PAGE_SIZE);
                    if (page.isEmpty()) break;
                    for (var e : page) {
                        if (sink.isCancelled()) return;
                        sink.next(e);
                        cursor = e.seq();
                    }
                    if (page.size() < QUERY_PAGE_SIZE) break;
                }
                if (!sink.isCancelled()) sink.complete();
            } catch (Exception e) {
                // 回放的失败必须**可见**：静默 complete 出一个空流，与「没有新事件」无法区分
                log.warn("SessionEventStore: queryAfter failed for {}: {}", sessionId, e.getMessage());
                sink.error(e);
            }
        });
    }

    /** 读取一页事件（已 materialize） */
    private List<EnvelopedEvent> queryPage(String sessionId, String replyId, int afterSeq, int limit) {
        // 下界钳到 0：XRANGE 的 ID 不能为负，而 afterSeq = -5 会让 afterSeq+1 = -4 直接报
        // 「Invalid stream ID」（实测）。0-0 是合法最小 ID，等价于「从头开始」。
        int from = Math.max(0, afterSeq + 1);

        if (replyId == null || replyId.isBlank()) {
            return toEnveloped(eventLog.range(sessionId, from, null, limit));
        }

        var boundaries = eventLog.replies(sessionId);
        int idx = indexOfReply(boundaries, replyId);
        if (idx < 0) {
            // 索引里没有这个 replyId。两种情况必须分开处置：
            //  - afterSeq <= 0：全新 turn 刚生成 replyId、还没写入任何事件。**空是正解**，
            //    而且这条路径是热路径（每来一次 POST /threads/chat 都会走），必须 O(turns)。
            //  - afterSeq > 0：客户端正在续传一个**已经开始过**的 reply，索引里却没有它——
            //    只能是索引丢了。这时宁可慢（退化成全流扫描）也不能返回空：空是**静默内容丢失**。
            if (afterSeq <= 0) {
                return List.of();
            }
            log.warn("SessionEventStore: reply {} 不在索引中但 afterSeq={} > 0（索引可能丢失），"
                + "本次回放退化为全流扫描。session={}", replyId, afterSeq, sessionId);
            return scanFilteredByReply(sessionId, replyId, from, limit);
        }

        int start = Math.max(boundaries.get(idx).firstSeq(), from);
        int end = idx + 1 < boundaries.size()
            ? boundaries.get(idx + 1).firstSeq() - 1
            : Integer.MAX_VALUE;
        if (start > end) {
            return List.of();
        }
        return toEnveloped(eventLog.range(sessionId, start, end, limit));
    }

    /** 仅在索引丢失时走的兜底路径：按页扫流、客户端过滤，直到攒够 limit 条或流结束。 */
    private List<EnvelopedEvent> scanFilteredByReply(String sessionId, String replyId,
                                                     int from, int limit) {
        var out = new ArrayList<EnvelopedEvent>();
        int cursor = from;
        while (out.size() < limit) {
            var page = eventLog.range(sessionId, cursor, null, QUERY_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (var e : page) {
                cursor = e.seq() + 1;
                if (replyId.equals(e.replyId())) {
                    out.add(toEnveloped(e));
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
            if (page.size() < QUERY_PAGE_SIZE) {
                break;
            }
        }
        return out;
    }

    private static int indexOfReply(List<RedisEventLog.ReplyBoundary> boundaries, String replyId) {
        for (int i = 0; i < boundaries.size(); i++) {
            if (replyId.equals(boundaries.get(i).replyId())) {
                return i;
            }
        }
        return -1;
    }

    private static List<EnvelopedEvent> toEnveloped(List<RedisEventLog.Event> events) {
        var out = new ArrayList<EnvelopedEvent>(events.size());
        for (var e : events) {
            out.add(toEnveloped(e));
        }
        return out;
    }

    private static EnvelopedEvent toEnveloped(RedisEventLog.Event e) {
        return new EnvelopedEvent(e.seq(), e.type(), e.payload(), e.replyId());
    }

    /**
     * 查询 session 最新一条事件（status 端点判断 turn 状态用）。
     *
     * <p><b>宽容版</b>：查不到或存储不可用时返回 null。适用于轮询路径（
     * {@code SessionEventTailer.probe}）——那里「读不到」应该静默跳过这一轮，
     * 由游标在下一轮补上。
     *
     * @return 最新事件；无记录或读取失败时返回 null
     */
    public EnvelopedEvent findLatest(String sessionId) {
        try {
            return findLatestStrict(sessionId);
        } catch (Exception e) {
            log.warn("SessionEventStore: findLatest failed for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 查询 session 最新一条事件，失败时**抛**。
     *
     * <p>给 {@code GET /threads/{sid}/status} 用：那里必须能把「没有事件」和「读不到」
     * 分开，否则存储不可用时会返回一个 {@code latest_event_seq: 0} 的**成功**响应，
     * 而前端 {@code chat.js} 会拿它把健康客户端的游标**重置为 0**——Redis 一恢复就是
     * 整场会话重放。宁可 503（前端已有对应的降级分支：保留游标、跳过恢复）。
     */
    public EnvelopedEvent findLatestStrict(String sessionId) {
        var e = eventLog.latest(sessionId);
        return e != null ? toEnveloped(e) : null;
    }

    /**
     * 查询 session 的最大 seq（= 流顶端）；读取失败时返回 0（宽容）。
     *
     * <p><b>当前 src/main 里没有调用方。</b>{@code /subscribe} 走
     * {@link SessionEventTailer#tail} 的 {@code queryAfter} 游标路径，{@code /status} 直接取
     * {@code latestEvent.seq()}（那条就是最大 seq），所以 F7 之后这里不再有重复查询。
     * 仅由若干测试的桩引用（{@code SessionStreamControllerTest} / {@code ChatStreamControllerTest} /
     * {@code ConfirmStreamControllerTest}）——保留它是为了不让这些测试桩失效，
     * 而不是因为它还被生产代码需要。
     */
    public int findMaxSeq(String sessionId) {
        try {
            return eventLog.tailSeq(sessionId);
        } catch (Exception e) {
            log.warn("SessionEventStore: findMaxSeq failed for {}: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 该 session 出现过的 replyId，按**首个事件的 seq** 升序
     * （等价于原来的 {@code GROUP BY reply_id ORDER BY MIN(seq)}）。
     *
     * <p>失败时抛：调用方（{@code ThreadController} 的 history 回填）需要区分
     * 「这个 session 没有 reply」与「读不到」——后者要 WARN 且让消息缺 reply_id，
     * 而不是静默当成空列表。
     */
    public List<String> findReplyIds(String sessionId) {
        var out = new ArrayList<String>();
        for (var b : eventLog.replies(sessionId)) {
            out.add(b.replyId());
        }
        return out;
    }

    /**
     * 删除该 session 的全部事件数据（级联删除用），并清掉本进程内的计数器与缓冲。
     *
     * @return 删除的 Redis key 数；失败时返回 -1
     */
    public int deleteSession(String sessionId) {
        synchronized (pending) {
            pending.remove(sessionId);
        }
        releaseSeq(sessionId);
        try {
            return eventLog.deleteSession(sessionId);
        } catch (Exception e) {
            log.warn("SessionEventStore: deleteSession failed for {}: {}", sessionId, e.getMessage());
            return -1;
        }
    }

    /** 留存天数（现在是 Redis key TTL 的来源） */
    public int retentionDays() {
        return retentionDays;
    }

    /** 留存时长（供日志/诊断打印） */
    public Duration retention() {
        return Duration.ofDays(retentionDays);
    }

    /**
     * 带序号的包装事件（对应 durable-sse-plan §3.2 EnvelopedEvent）。
     *
     * @param seq       事件序号（session 内递增，回放游标用）
     * @param type      事件类型（AGENT_END / TEXT_BLOCK_DELTA / permission_ask / ...）
     * @param payload   SSE data 字段的 JSON（与实时推送词表一致）
     * @param replyId   turn 标识（区分 HITL 恢复等多 run）
     */
    public record EnvelopedEvent(int seq, String type, String payload, String replyId) {}
}
