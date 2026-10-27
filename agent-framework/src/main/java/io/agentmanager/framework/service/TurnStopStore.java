package io.agentmanager.framework.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 单轮停止请求存储（turn_stop 表，POST /threads/{sessionId}/stop）。
 *
 * <h2>为什么必须落共享存储</h2>
 * SDK 的 interrupt 标志挂在 {@code AgentState.interruptControl()} 上，**只在进程内**
 * 有效（{@code InterruptControl} javadoc 明示 runtime-only、不随 state 序列化）；
 * 停止请求可能被负载均衡打到任意副本，而 turn 只在一个副本上执行。请求方写行、
 * 属主副本轮询读行并调用 {@code agent.interrupt(userId, sessionId)}，跨副本无需
 * 粘性路由（架构原则二）。
 *
 * <h2>reply_id 栅栏</h2>
 * 行以 session_id 为 PK（同会话只保留最新一次停止请求），并携带本轮 turn 的
 * {@code reply_id}。属主只消费与自己 reply_id 完全一致的行——新 turn 的 reply_id
 * 必然不同，陈旧行不会误杀下一轮。
 *
 * <h2>存储错误分型</h2>
 * {@link #request} 区分「已写入」与「暂时不可判定」（UNAVAILABLE），端点据此返回
 * 503 而不是谎称停止成功；读取路径（{@link #find} / {@link #findAll}）失败时返回
 * 空集（属主下一拍重试，宁延迟不误判）。
 */
@Service
public class TurnStopStore {

    private static final Logger log = LoggerFactory.getLogger(TurnStopStore.class);

    /** 读时过滤窗口：超过该时长的行视为孤儿（崩溃遗留），不参与派发 */
    private static final long STALE_WINDOW_SECONDS = 3600;

    /** 停止请求写入结果 */
    public enum RequestOutcome {
        /** 行已写入（可被属主副本的轮询消费） */
        REQUESTED,
        /** 存储故障：无法确认请求已落地（调用方应返回 503） */
        UNAVAILABLE
    }

    /** 一条停止请求（session_id + 栅栏 reply_id） */
    public record StopRequest(String sessionId, String replyId) {}

    private final DataSource dataSource;

    public TurnStopStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 写入/覆盖本会话的停止请求（同会话 last-writer-wins）。
     *
     * <p>同会话重复点击停止是幂等覆盖：reply_id 更新为最新解析出的目标轮次。
     */
    public RequestOutcome request(String sessionId, String replyId, String requestedBy) {
        if (sessionId == null || sessionId.isBlank() || replyId == null || replyId.isBlank()) {
            return RequestOutcome.UNAVAILABLE;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                 INSERT INTO turn_stop (session_id, reply_id, requested_by, requested_at)
                 VALUES (?, ?, ?, NOW(3))
                 ON DUPLICATE KEY UPDATE
                     reply_id = VALUES(reply_id),
                     requested_by = VALUES(requested_by),
                     requested_at = VALUES(requested_at)
                 """)) {
            ps.setString(1, sessionId);
            ps.setString(2, replyId);
            ps.setString(3, requestedBy);
            ps.executeUpdate();
            return RequestOutcome.REQUESTED;
        } catch (Exception e) {
            log.error("TurnStopStore: request failed for sid={}, rid={}: {}",
                sessionId, replyId, e.getMessage());
            return RequestOutcome.UNAVAILABLE;
        }
    }

    /**
     * 查询本会话的停止请求（排队 turn 的短轮询使用）。
     *
     * @return 请求；无行/已过期/存储不可用时为 null（调用方下一拍重试）
     */
    public StopRequest find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                 SELECT reply_id
                   FROM turn_stop
                  WHERE session_id = ?
                    AND requested_at > DATE_SUB(NOW(3), INTERVAL ? SECOND)
                 """)) {
            ps.setString(1, sessionId);
            ps.setLong(2, STALE_WINDOW_SECONDS);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? new StopRequest(sessionId, rs.getString("reply_id")) : null;
            }
        } catch (Exception e) {
            log.warn("TurnStopStore: find failed for sid={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 全量读取在窗停止请求（属主副本的全局轮询使用：一次查询派发给本 Pod 所有活跃 turn，
     * 查询成本与并发 turn 数解耦）。
     */
    public List<StopRequest> findAll() {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                 SELECT session_id, reply_id
                   FROM turn_stop
                  WHERE requested_at > DATE_SUB(NOW(3), INTERVAL ? SECOND)
                  LIMIT 5000
                 """)) {
            ps.setLong(1, STALE_WINDOW_SECONDS);
            try (var rs = ps.executeQuery()) {
                var rows = new ArrayList<StopRequest>();
                while (rs.next()) {
                    rows.add(new StopRequest(rs.getString("session_id"), rs.getString("reply_id")));
                }
                return rows;
            }
        } catch (Exception e) {
            log.warn("TurnStopStore: findAll failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 消费停止请求（属主 turn 收尾时调用）。
     *
     * <p>只删 reply_id 匹配的行：游标推进到下一轮后，迟到的清理不得删除新请求。
     */
    public boolean consume(String sessionId, String replyId) {
        if (sessionId == null || sessionId.isBlank() || replyId == null || replyId.isBlank()) {
            return false;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "DELETE FROM turn_stop WHERE session_id = ? AND reply_id = ?")) {
            ps.setString(1, sessionId);
            ps.setString(2, replyId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            log.warn("TurnStopStore: consume failed for sid={}, rid={}: {}",
                sessionId, replyId, e.getMessage());
            return false;
        }
    }

    /** 清理孤儿停止请求（SessionCleanupService 每日兜底）。 */
    public int deleteBefore(Instant cutoff) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "DELETE FROM turn_stop WHERE requested_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("TurnStopStore: deleteBefore failed: {}", e.getMessage());
            return 0;
        }
    }
}
