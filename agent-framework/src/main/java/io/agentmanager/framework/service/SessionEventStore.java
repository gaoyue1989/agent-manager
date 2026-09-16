package io.agentmanager.framework.service;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * 事件持久化存储（session_event 表，durable-sse-plan §3.1）。
 *
 * <p>将 agent 执行过程中的 SSE 事件持久化，供前端断连后通过
 * {@code GET /threads/{sid}/subscribe?afterSeq=N} 回放增量事件并续传。
 *
 * <p>存储内容复用 {@link AgentEventSseSerializer#payload} 的 JSON 输出，
 * 保证回放词表与实时推送完全一致，前端渲染逻辑零改动。
 *
 * <p>保留期：默认 7 天（与 agent_state 对齐），SessionCleanupService 联动清理。
 */
@Service
public class SessionEventStore {

    private static final Logger log = LoggerFactory.getLogger(SessionEventStore.class);

    /** delta 类事件：进缓冲攒批，不逐条落库 */
    private static final Set<String> DELTA_EVENT_TYPES =
        Set.of("TEXT_BLOCK_DELTA", "THINKING_BLOCK_DELTA");

    /** 默认批量大小（行数） */
    private static final int DEFAULT_BATCH_SIZE = 200;

    /** 默认攒批时间窗（毫秒）：超过则把缓冲刷出，限制重连回放的滞后 */
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 1000;

    /** 回放分页大小：限制单次查询 materialize 的行数 */
    private static final int QUERY_PAGE_SIZE = 500;

    /** 预检发现重复行时打印的排查 SQL（人工去重用；本类**不**自动删数据） */
    private static final String REMEDIATION_SQL =
        "SELECT session_id, seq, COUNT(*) AS c, GROUP_CONCAT(id ORDER BY id) AS ids "
            + "FROM session_event GROUP BY session_id, seq HAVING c > 1;";

    /** 回放分页大小（测试可见） */
    static int queryPageSize() {
        return QUERY_PAGE_SIZE;
    }

    private final DataSource dataSource;
    private final int retentionDays;
    private final int batchSize;
    private final int flushIntervalMs;

    /**
     * session_id → 下一个待分配的 seq。
     *
     * <p>seq 是 session 维度递增的，而 turn 会跨副本交接（HITL 恢复产生新 turn、
     * 新 replyId，可能在另一个 Pod 上执行）。因此计数器必须在**每个 turn 开始时**
     * 从 DB 当前最大值续起，不能只在 Pod 启动时初始化一次。
     *
     * <p>安全性依赖：同一 session 的 turn 由 turn_lease 全局串行化，任一时刻只有一个
     * Pod 在写该 session。若该前提被放松，本计数器会产生 seq 冲突。
     */
    private final ConcurrentHashMap<String, AtomicInteger> seqCounters = new ConcurrentHashMap<>();

    /** 一个 session 的待落库缓冲 */
    private static final class Buffer {
        final List<PendingRow> rows = new ArrayList<>();
        long lastFlushAt = System.currentTimeMillis();
    }

    /**
     * session_id → 待落库缓冲；访问一律持 {@code pending} 监视器。
     *
     * <p><b>按 session 分区</b>，而不是一条全局 FIFO。原因是 InnoDB 按**语句**回滚：
     * 只有「一条 INSERT 只装一个 session 的行」，seq 冲突（uk_session_seq）的影响面才
     * 止于肇事 session。全局缓冲下一批混着多个 session，一条冲突会连带丢掉邻居的行
     * ——那是把「静默重复」换成「静默丢失 + 殃及邻居」，更糟。
     *
     * <p>分区同时让 {@code finishTurn(A)} 只刷 A 的行，不再顺带刷出（或丢掉）其他 session 的行。
     */
    private final Map<String, Buffer> pending = new LinkedHashMap<>();

    /** 攒批缓冲中的一行（尚未落库） */
    private record PendingRow(String sessionId, int seq, String replyId,
                              String eventType, String payload) {}

    public SessionEventStore(DataSource dataSource) {
        this(dataSource, 7, DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public SessionEventStore(DataSource dataSource, int retentionDays) {
        this(dataSource, retentionDays, DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public SessionEventStore(DataSource dataSource, int retentionDays,
                             int batchSize, int flushIntervalMs) {
        this.dataSource = dataSource;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.flushIntervalMs = flushIntervalMs >= 0 ? flushIntervalMs : DEFAULT_FLUSH_INTERVAL_MS;
        initSchema();
    }

    /** 建表（幂等），失败 fail-fast */
    private void initSchema() {
        try (var conn = dataSource.getConnection()) {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS session_event (
                      id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                      session_id  VARCHAR(255) NOT NULL,
                      seq         INT NOT NULL,
                      event_type  VARCHAR(64) NOT NULL,
                      payload     MEDIUMTEXT NOT NULL,
                      reply_id    VARCHAR(64),
                      created_at  DATETIME(3) NOT NULL,
                      UNIQUE KEY uk_session_seq (session_id, seq),
                      KEY idx_session_reply (session_id, reply_id, seq),
                      KEY idx_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
                log.info("SessionEventStore: session_event table ready");
            }
            // 既有库补键。放在 CREATE 之后、且自己吞掉全部异常：建表失败该 fail-fast，
            // 补键失败不该（见 ensureUniqueSeqKey 的 javadoc）
            ensureUniqueSeqKey(conn);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init session_event table: " + e.getMessage(), e);
        }
    }

    /**
     * 幂等补 {@code uk_session_seq}（既有库用）；新库的 DDL 已带该键，此处直接返回。
     *
     * <p>这是 I2（turn_lease 保证单 writer）的**唯一兜底**：租约丢失到被察觉之间有一个续租
     * 周期（默认 20s）的窗口，期间的写入会与新 owner 的 seq 区间重叠。没有这个键，重叠表现为
     * 静默插入重复行（回放时前端看到重复事件）；有了它，表现为一次响亮失败的 INSERT。
     *
     * <p><b>本方法永不抛异常</b>：补索引失败只该少一层兜底，不该让服务起不来。
     */
    private void ensureUniqueSeqKey(java.sql.Connection conn) {
        try {
            if (hasIndex(conn, "uk_session_seq")) {
                return;
            }

            // 预检：有重复则 ALTER 必然失败（已在真实库的 scratch 表上验证过 1062）。
            // 按既定策略「响亮跳过」——不自动删数据、不 abort 启动，交人工处置。
            var dups = findDuplicateSeqGroups(conn, 20);
            if (!dups.isEmpty()) {
                log.error("SessionEventStore: 检测到 {} 组重复的 (session_id, seq)，"
                    + "本次**不创建**唯一键 uk_session_seq。重复行会让断线回放出现重复事件，"
                    + "请人工核对后去重再重启（服务已在无兜底状态下运行）。排查：{}",
                    dups.size(), REMEDIATION_SQL);
                for (var d : dups) {
                    log.error("  SessionEventStore: 重复组 session_id={}, seq={}, 行数={}",
                        d.sessionId(), d.seq(), d.count());
                }
                return;
            }

            // 两条 ALTER 各自独立守卫，而不是合成一条原子 DDL。合成的版本看着漂亮，但
            // 「ADD 成功 + DROP 因索引不存在而报错」会让整条语句回滚——兜底键就悄悄没了。
            // 拆开后，加键与删冗余索引互不牵连，各自失败各自 WARN。
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE session_event "
                    + "ADD UNIQUE KEY uk_session_seq (session_id, seq)");
                log.info("SessionEventStore: 已为 session_event 加上 UNIQUE KEY uk_session_seq");
            } catch (Exception e) {
                // 与 FileAssetStore 的既有 ALTER 同一取向：补索引失败不该拖垮启动
                log.warn("SessionEventStore: 添加唯一键失败（服务继续运行，仅少了这层兜底）: {}",
                    e.getMessage());
            }
            try (var stmt = conn.createStatement()) {
                // 唯一键前缀与普通索引完全相同，后者纯属写放大——这张表是全服务最热的写入点
                stmt.executeUpdate("ALTER TABLE session_event DROP INDEX idx_session_seq");
                log.info("SessionEventStore: 已删除被唯一键完全覆盖的 idx_session_seq");
            } catch (Exception e) {
                log.warn("SessionEventStore: 删除冗余索引 idx_session_seq 失败（无害，仅多一份写放大）: {}",
                    e.getMessage());
            }
        } catch (Exception e) {
            // mock 的 Connection 上 getMetaData() 返回 null（NPE）也落在这里；
            // 三个 store 单测都是 mock，这条路走不通就该安静跳过，而不是炸掉启动
            log.warn("SessionEventStore: 唯一键检查跳过（{}）", e.toString());
        }
    }

    /** 索引是否已存在（用 DatabaseMetaData，与 ThreadController.ensureRemarkColumn 同一手法） */
    private static boolean hasIndex(java.sql.Connection conn, String indexName) throws SQLException {
        try (var rs = conn.getMetaData()
                .getIndexInfo(null, null, "session_event", false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 找出重复的 (session_id, seq) 组，按重复行数降序（用于「响亮跳过」时的诊断输出） */
    private static List<DuplicateSeqGroup> findDuplicateSeqGroups(java.sql.Connection conn, int limit)
            throws SQLException {
        var out = new ArrayList<DuplicateSeqGroup>();
        try (var ps = conn.prepareStatement(
                "SELECT session_id, seq, COUNT(*) AS c FROM session_event "
                    + "GROUP BY session_id, seq HAVING c > 1 ORDER BY c DESC, session_id LIMIT ?")) {
            ps.setInt(1, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DuplicateSeqGroup(
                        rs.getString("session_id"), rs.getInt("seq"), rs.getInt("c")));
                }
            }
        }
        return out;
    }

    /** 一组重复的 (session_id, seq) */
    private record DuplicateSeqGroup(String sessionId, int seq, int count) {}

    /**
     * 播种 seq 计数器（turn 开始时调用，必须在本 Pod 获得 turn_lease 之后）。
     *
     * <p>幂等：计数器已存在时不做任何事，避免把已推进的计数器重置回 DB 的最大值
     * （缓冲区中尚未落库的行会因此被覆盖）。
     */
    public void seedSeq(String sessionId) {
        counterFor(sessionId);
    }

    /**
     * 释放 seq 计数器（底层原语，仅供 {@link #releaseSeqIfIdle} 与测试使用）。
     *
     * <p><b>收尾不要直接调它</b>：无条件释放会让缓冲里尚未落库的行所占的 seq 被下一次惰性
     * 播种重新发出去（见 {@link #releaseSeqIfIdle} 的 javadoc）。保留 public 是为了让测试
     * 能直接构造「计数器已释放」这一状态。
     */
    public void releaseSeq(String sessionId) {
        seqCounters.remove(sessionId);
    }

    /**
     * 收尾时释放 seq 计数器——**仅当该 session 没有待落库的行**。
     *
     * <p>释放必须**晚于** flush，否则惰性播种读到的 {@code MAX} 还差着刚落库的那批；但 flush
     * 是一次 INSERT 往返，而它不在 {@code pending} 临界区内：这段时间里到达的尾部事件会从
     * **尚未释放**的计数器拿到 seq（正确，且大于正在落库的那批），并落进一个新缓冲。此时若
     * 无条件释放，下一次 append 就改从 {@code MAX} 重新发号，而 {@code MAX} 看不见那行——于是
     * 同一个 seq 被发两次，整批撞 {@code uk_session_seq}。
     *
     * <p>重号还有第二种代价，更隐蔽：重新发号的起点可能**低于客户端已消费的游标**，
     * 那些事件在 {@code seq > cursor} 的回放里会被永久跳过。所以宁可让计数器多活一会儿
     * （由清扫兜底，见遗留 #12），也不要重号。
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

    /** 取该 session 的计数器，不存在则**惰性播种**自 DB 的最大 seq。幂等。 */
    private AtomicInteger counterFor(String sessionId) {
        return seqCounters.computeIfAbsent(sessionId, sid -> {
            int max = findMaxSeq(sid);
            log.debug("SessionEventStore: seeded seq counter for {} at {}", sid, max);
            return new AtomicInteger(max);
        });
    }

    /**
     * 取下一个 seq：**一律**走内存计数器，计数器不存在时在其上惰性播种。
     *
     * <p>这里不能退化成「每次 append 查一次 {@code MAX(seq) + 1}」：缓冲区里尚未落库的行对
     * {@code MAX} 是**不可见**的，于是同一个未播种 session 的后续每一行都会拿到同一个 seq，
     * 攒够一批就在多值 INSERT 里自我冲突——
     * {@code Duplicate entry ... for key 'uk_session_seq'}，整批被丢弃。
     *
     * <p>攒批落库之前这条退回路径恰好只对**第一行**成立（每次 append 立即 INSERT，MAX 看得见
     * 上一行），所以引入缓冲之后它就从「略慢」变成了「整批失败」。触发条件不是租约丢失，
     * 而是**计数器缺失**：{@code beginTurn} 未跑，或 {@code releaseSeq} 之后流仍在产出尾部事件。
     */
    private int nextSeq(String sessionId) {
        return counterFor(sessionId).incrementAndGet();
    }

    /**
     * 追加一条事件记录，返回分配的 seq。
     *
     * <p>delta 类事件（TEXT_BLOCK_DELTA / THINKING_BLOCK_DELTA）进缓冲攒批；里程碑事件
     * 触发一次 flush，把缓冲与自身放在**同一条多值 INSERT** 中落库。
     *
     * <p>不变量 I1（DB 中的 seq 构成连续前缀）在**每批都落库成功**时成立：里程碑与它
     * 之前的 delta 同批落库，不会出现"里程碑已落库但中间的 delta 缺失"。
     *
     * <p>已知偏差：某批 INSERT 失败时该批的行被整体丢弃，且 seq 计数器**不回退**，
     * 随后成功的批次会从更高的 seq 继续，DB 中因此留下空洞（1,2,3 成功 → 4,5 失败 →
     * 下一批 6,7，落库序列 [1,2,3,6,7]）。不回退是有意的：可能有并发 append 已推进
     * 计数器，回退会直接造成 seq 冲突。空洞不影响续传——回放按 {@code seq > cursor}
     * 读取，不依赖连续性。分区的价值在于：这次丢弃**只波及本 session**。
     *
     * <p>注意：本方法不在持有连接的情况下调用 {@link #nextSeq(String)}，其惰性播种路径上的
     * {@link #findMaxSeq(String)} 会自行借用连接，不会同时占用两条连接。
     *
     * @return 分配的 seq（从 1 开始递增）；-1 表示落库失败
     */
    public int append(String sessionId, String replyId, String eventType, String payload) {
        int seq = nextSeq(sessionId);
        var row = new PendingRow(sessionId, seq, replyId, eventType, payload);

        List<PendingRow> toFlush = null;
        synchronized (pending) {
            var buf = pending.computeIfAbsent(sessionId, k -> new Buffer());
            buf.rows.add(row);
            long now = System.currentTimeMillis();
            boolean milestone = !DELTA_EVENT_TYPES.contains(eventType);
            // 三个条件都只按**本 session** 的行数/时间窗评估：别的 session 攒了多少与
            // 本 session 的回放滞后无关，不该由它触发刷出
            boolean full = buf.rows.size() >= batchSize;
            boolean timedOut = now - buf.lastFlushAt >= flushIntervalMs;
            if (milestone || full || timedOut) {
                toFlush = new ArrayList<>(buf.rows);
                buf.rows.clear();
                buf.lastFlushAt = now;
            }
        }

        if (toFlush != null && insertBatch(toFlush) < 0) {
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
        insertBatch(toFlush);
        releaseSeqIfIdle(sessionId);
    }

    /**
     * turn 因**租约丢失**而终止：丢弃该 session 的待落库行（不落库）并释放 seq 计数器。
     *
     * <p>与 {@link #finishTurn} 的唯一差别就是不 flush——缓冲里那些行的 seq 是本副本
     * 「以为自己还持锁」时分配的，而此刻新 owner 可能已在同一区间分配过 seq。把它们
     * 写下去正是丢租约要防的事：有唯一键则是一次响亮的批失败，没有则静默写重复行。
     *
     * <p>这是**有损**的：客户端看到的事件流会少一段尾巴。但那段尾巴本来就不该属于本次
     * turn 的序号空间——被接管的副本会把完整内容重新产出。
     */
    public void abandonTurn(String sessionId) {
        int dropped = 0;
        synchronized (pending) {
            var buf = pending.remove(sessionId);
            if (buf != null) {
                dropped = buf.rows.size();
            }
        }
        // 丢弃窗口内到达的尾部事件同样要按「有缓冲就不放号」处理：丢弃让计数器停在了
        // MAX 之上，重新发号会从 MAX 起重来，可能低于客户端已消费的游标
        releaseSeqIfIdle(sessionId);
        if (dropped > 0) {
            log.warn("SessionEventStore: turn abandoned (lease lost, sid={}) — "
                + "dropped {} buffered row(s) without writing", sessionId, dropped);
        }
    }

    /**
     * 一条多值 INSERT 写入多行。
     *
     * @return 受影响行数；&lt; 0 表示失败
     */
    private int insertBatch(List<PendingRow> rows) {
        if (rows.isEmpty()) return 0;
        var sql = new StringBuilder(
            "INSERT INTO session_event (session_id, seq, event_type, payload, reply_id, created_at) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append("(?,?,?,?,?,NOW(3))");
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (var r : rows) {
                ps.setString(idx++, r.sessionId());
                ps.setInt(idx++, r.seq());
                ps.setString(idx++, r.eventType());
                ps.setString(idx++, r.payload());
                ps.setString(idx++, r.replyId());
            }
            return ps.executeUpdate();
        } catch (java.sql.SQLIntegrityConstraintViolationException e) {
            // 撞 uk_session_seq：本批的 (session_id, seq) 与库中已落库的行冲突。这是**最该响亮**
            // 的一种失败，单独一条日志是为了区别于「DB 抖动」那种可自愈的失败。
            //
            // 日志把判据（批内不同 seq 数）一并打出来，而不是只给一个结论：曾经这里无条件宣告
            // 「第二个 writer」，而实际撞上的是「批内 seq 全同」——那是本 Pod 计数器缺失导致的
            // 自我冲突，与租约无关，按前者去查租约只会把排查带偏。先给证据，再给假设。
            long distinct = rows.stream().mapToInt(PendingRow::seq).distinct().count();
            log.error("SessionEventStore: 唯一键冲突——session {} 本批 {} 行（seq {}..{}，批内不同 seq 数 {}）"
                + "已整批丢弃，未落库。seq 区间与库中已落库的行重叠，通常意味着某副本丢失 turn_lease "
                + "后仍继续写入（I2 被违反）；请核对 turn 租约与副本时钟。"
                + "若「批内不同 seq 数」为 1 且行数 > 1，则不是第二个 writer——是本 Pod 的 seq 计数器与"
                + "缓冲不同步（beginTurn/seedSeq 未在该 session 上执行，或收尾与尾部事件并发），"
                + "整批撞在了同一个 seq 上。",
                rows.get(0).sessionId(), rows.size(),
                rows.get(0).seq(), rows.get(rows.size() - 1).seq(), distinct, e);
            return -1;
        } catch (Exception e) {
            log.error("SessionEventStore: batch insert failed ({} rows, first session={}): {}",
                rows.size(), rows.get(0).sessionId(), e.getMessage());
            // 持久化失败不应阻塞主链路——EventBus 仍可广播实时事件
            return -1;
        }
    }

    /**
     * 查询 afterSeq 之后的事件（回放用）。
     *
     * <p>分页读取：每页在**独立连接内 materialize 成 List 后立即释放连接**，再从内存中
     * 向外发。避免慢客户端反压时把数据库连接钉在整个推送期间（连接池默认仅 10）。
     *
     * @param afterSeq 游标：返回 seq > afterSeq 的记录
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
                log.warn("SessionEventStore: queryAfter failed for {}: {}", sessionId, e.getMessage());
                sink.error(e);
            }
        });
    }

    /** 读取一页事件并 materialize 成 List（连接在方法返回前关闭） */
    private List<EnvelopedEvent> queryPage(String sessionId, String replyId, int afterSeq, int limit)
            throws SQLException {
        boolean byReply = replyId != null && !replyId.isBlank();
        String sql = byReply
            ? "SELECT seq, event_type, payload, reply_id FROM session_event "
                + "WHERE session_id = ? AND reply_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?"
            : "SELECT seq, event_type, payload, reply_id FROM session_event "
                + "WHERE session_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?";

        var out = new ArrayList<EnvelopedEvent>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, sessionId);
            if (byReply) ps.setString(i++, replyId);
            ps.setInt(i++, afterSeq);
            ps.setInt(i, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EnvelopedEvent(
                        rs.getInt("seq"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("reply_id")));
                }
            }
        }
        return out;
    }

    /**
     * 查询 session 最新一条事件（status 端点判断 turn 状态用）。
     *
     * @return 最新事件，无记录时返回 null
     */
    public EnvelopedEvent findLatest(String sessionId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT seq, event_type, payload, reply_id FROM session_event "
                     + "WHERE session_id = ? ORDER BY seq DESC LIMIT 1")) {
            ps.setString(1, sessionId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                return new EnvelopedEvent(
                    rs.getInt("seq"),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getString("reply_id"));
            }
            return null;
        } catch (Exception e) {
            log.warn("SessionEventStore: findLatest failed for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 查询 session 的最大 seq（subscribe 端点用） */
    public int findMaxSeq(String sessionId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT COALESCE(MAX(seq), 0) FROM session_event WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            log.warn("SessionEventStore: findMaxSeq failed for {}: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /** 清理保留期之外的事件记录（SessionCleanupService 调用） */
    public int deleteBefore(Instant cutoff) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "DELETE FROM session_event WHERE created_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            var n = ps.executeUpdate();
            if (n > 0) {
                log.info("SessionEventStore: cleaned {} event(s) before {}", n, cutoff);
            }
            return n;
        } catch (Exception e) {
            log.warn("SessionEventStore: deleteBefore failed: {}", e.getMessage());
            return 0;
        }
    }

    public int retentionDays() {
        return retentionDays;
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
