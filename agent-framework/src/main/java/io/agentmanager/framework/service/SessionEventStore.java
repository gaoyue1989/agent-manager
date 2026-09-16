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

    /** 待落库的行（delta 缓冲）；访问一律持 pending 监视器 */
    private final List<PendingRow> pending = new ArrayList<>();

    private long lastFlushAt = System.currentTimeMillis();

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
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS session_event (
                  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                  session_id  VARCHAR(255) NOT NULL,
                  seq         INT NOT NULL,
                  event_type  VARCHAR(64) NOT NULL,
                  payload     MEDIUMTEXT NOT NULL,
                  reply_id    VARCHAR(64),
                  created_at  DATETIME(3) NOT NULL,
                  KEY idx_session_seq (session_id, seq),
                  KEY idx_session_reply (session_id, reply_id, seq),
                  KEY idx_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            log.info("SessionEventStore: session_event table ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init session_event table: " + e.getMessage(), e);
        }
    }

    /**
     * 播种 seq 计数器（turn 开始时调用，必须在本 Pod 获得 turn_lease 之后）。
     *
     * <p>幂等：计数器已存在时不做任何事，避免把已推进的计数器重置回 DB 的最大值
     * （缓冲区中尚未落库的行会因此被覆盖）。
     */
    public void seedSeq(String sessionId) {
        seqCounters.computeIfAbsent(sessionId, sid -> {
            int max = findMaxSeq(sid);
            log.debug("SessionEventStore: seeded seq counter for {} at {}", sid, max);
            return new AtomicInteger(max);
        });
    }

    /** 释放 seq 计数器（turn 结束时调用，防止 map 无界增长） */
    public void releaseSeq(String sessionId) {
        seqCounters.remove(sessionId);
    }

    /** 取下一个 seq：已播种走内存计数器，未播种退回 DB 查询 */
    private int nextSeq(String sessionId) {
        var counter = seqCounters.get(sessionId);
        if (counter != null) {
            return counter.incrementAndGet();
        }
        return findMaxSeq(sessionId) + 1;
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
     * 下一批 6,7，落库序列 [1,2,3,6,7]）。不回退是有意的：同一批里还混着其他 session
     * 的行，且可能有并发 append 已推进计数器，回退会直接造成 seq 冲突。空洞不影响续传
     * ——回放按 {@code seq > cursor} 读取，不依赖连续性。
     *
     * <p>注意：本方法不在持有连接的情况下调用 {@link #nextSeq(String)}，未播种路径
     * 上的 {@link #findMaxSeq(String)} 会各自借用连接，不会同时占用两条连接。
     *
     * @return 分配的 seq（从 1 开始递增）；-1 表示落库失败
     */
    public int append(String sessionId, String replyId, String eventType, String payload) {
        int seq = nextSeq(sessionId);
        var row = new PendingRow(sessionId, seq, replyId, eventType, payload);

        List<PendingRow> toFlush = null;
        synchronized (pending) {
            pending.add(row);
            long now = System.currentTimeMillis();
            boolean milestone = !DELTA_EVENT_TYPES.contains(eventType);
            boolean full = pending.size() >= batchSize;
            boolean timedOut = now - lastFlushAt >= flushIntervalMs;
            if (milestone || full || timedOut) {
                toFlush = new ArrayList<>(pending);
                pending.clear();
                lastFlushAt = now;
            }
        }

        if (toFlush != null && insertBatch(toFlush) < 0) {
            return -1;
        }
        return seq;
    }

    /**
     * turn 结束：把缓冲刷出并释放 seq 计数器。
     * 必须由 turn 的终态路径调用，否则尾部 delta 会一直留在内存中直到下一个 turn。
     */
    public void finishTurn(String sessionId) {
        flushPending();
        releaseSeq(sessionId);
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
        int dropped = discardPending(sessionId);
        releaseSeq(sessionId);
        if (dropped > 0) {
            log.warn("SessionEventStore: turn abandoned (lease lost, sid={}) — "
                + "dropped {} buffered row(s) without writing", sessionId, dropped);
        }
    }

    /** 丢弃某 session 的待落库行，返回丢弃行数（**不**落库） */
    private int discardPending(String sessionId) {
        int dropped = 0;
        synchronized (pending) {
            var it = pending.iterator();
            while (it.hasNext()) {
                if (sessionId.equals(it.next().sessionId())) {
                    it.remove();
                    dropped++;
                }
            }
        }
        return dropped;
    }

    /** 刷出缓冲中所有待落库的行 */
    public void flushPending() {
        List<PendingRow> toFlush;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            toFlush = new ArrayList<>(pending);
            pending.clear();
            lastFlushAt = System.currentTimeMillis();
        }
        insertBatch(toFlush);
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
