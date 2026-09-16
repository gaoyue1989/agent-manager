package io.agentmanager.framework.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final DataSource dataSource;
    private final int retentionDays;

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

    public SessionEventStore(DataSource dataSource, int retentionDays) {
        this.dataSource = dataSource;
        this.retentionDays = retentionDays;
        initSchema();
    }

    public SessionEventStore(DataSource dataSource) {
        this(dataSource, 7);
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
     * <p>同 session 的 turn 由 turn_lease 串行化，seq 分配无需额外锁。
     * 不同 turn（HITL 恢复）的 reply_id 不同，不会冲突。
     *
     * @return 分配的 seq（从 1 开始递增）
     */
    public int append(String sessionId, String replyId, String eventType, String payload) {
        try (var conn = dataSource.getConnection()) {
            int nextSeq = nextSeq(sessionId);

            try (var ps = conn.prepareStatement("""
                INSERT INTO session_event
                  (session_id, seq, event_type, payload, reply_id, created_at)
                VALUES (?, ?, ?, ?, ?, NOW(3))
                """)) {
                ps.setString(1, sessionId);
                ps.setInt(2, nextSeq);
                ps.setString(3, eventType);
                ps.setString(4, payload);
                ps.setString(5, replyId);
                ps.executeUpdate();
            }
            return nextSeq;
        } catch (Exception e) {
            log.error("SessionEventStore: append failed for {}: {}", sessionId, e.getMessage());
            // 持久化失败不应阻塞主链路——EventBus 仍可广播实时事件
            return -1;
        }
    }

    /**
     * 查询 afterSeq 之后的事件（回放用）。
     *
     * @param afterSeq 游标：返回 seq > afterSeq 的记录；0 表示不回放
     * @param replyId  turn 标识（可选）；null 表示不限 turn
     */
    public Flux<EnvelopedEvent> queryAfter(String sessionId, String replyId, int afterSeq) {
        String sql;
        if (replyId != null && !replyId.isBlank()) {
            sql = "SELECT seq, event_type, payload, reply_id FROM session_event "
                + "WHERE session_id = ? AND reply_id = ? AND seq > ? ORDER BY seq ASC";
        } else {
            sql = "SELECT seq, event_type, payload, reply_id FROM session_event "
                + "WHERE session_id = ? AND seq > ? ORDER BY seq ASC";
        }

        return Flux.create(sink -> {
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(sql)) {
                if (replyId != null && !replyId.isBlank()) {
                    ps.setString(1, sessionId);
                    ps.setString(2, replyId);
                    ps.setInt(3, afterSeq);
                } else {
                    ps.setString(1, sessionId);
                    ps.setInt(2, afterSeq);
                }
                var rs = ps.executeQuery();
                while (rs.next() && !sink.isCancelled()) {
                    sink.next(new EnvelopedEvent(
                        rs.getInt("seq"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("reply_id")));
                }
                sink.complete();
            } catch (Exception e) {
                log.warn("SessionEventStore: queryAfter failed for {}: {}", sessionId, e.getMessage());
                sink.error(e);
            }
        });
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
