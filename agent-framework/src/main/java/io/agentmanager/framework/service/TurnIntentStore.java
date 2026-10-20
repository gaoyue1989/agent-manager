package io.agentmanager.framework.service;

import java.time.Duration;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 排队 turn 意图存储（turn_intent 表）。
 *
 * <h2>为什么需要</h2>
 * {@code POST /threads/chat} 在抢 {@code turn_lease} 前就要先落一行意图：同会话并发时
 * 新请求可能排队数十秒，排队期间它还没有任何 session_event（事件要等真正开跑才产生）。
 * 若此时用户刷新页面，{@code /status} 只能看到**前一个** turn 的事件与 replyId，前端按
 * 旧 turn 过滤续传就永远等不到自己的输出。意图行把「本轮 turn id」提前暴露给任意副本，
 * 使排队中的刷新也能正确续传。
 *
 * <h2>状态机</h2>
 * <ul>
 *   <li>{@code QUEUED}：已登记、等待租约；{@code /status} 返回 {@code working} +
 *       本 turn 的 {@code reply_id}，{@code /subscribe} 保持连接等事件。</li>
 *   <li>{@code FAILED}：排队超时或建立阶段失败；{@code /status} 返回 {@code failed} +
 *       {@code error}，已订阅的观察者流收到 error 帧并收流（不需要把错误写进事件流）。</li>
 * </ul>
 * 租约到手即删除意图行（此后 turn id 由 session_event 的 reply_id 承担）；崩溃遗留行
 * 由 {@code expires_at} 读时过滤 + 每日清理兜底。所有写操作以 {@code reply_id} 做
 * token 校验：旧请求的失败/清理不得覆盖或删除更新的 turn 意图。
 */
@Service
public class TurnIntentStore {

    private static final Logger log = LoggerFactory.getLogger(TurnIntentStore.class);

    /** 状态取值（与迁移文件注释一致） */
    public static final String STATE_QUEUED = "QUEUED";
    public static final String STATE_FAILED = "FAILED";

    /** 一行意图（{@code failed} 时 {@code error} 为失败原因） */
    public record Intent(String replyId, boolean failed, String error) {}

    private final DataSource dataSource;

    public TurnIntentStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 登记/覆盖排队意图（同一 session 只保留最新一条，last-writer-wins）。
     *
     * @param ttl 意图有效期：应覆盖整个排队窗口（调用方取租约等待超时 + 余量）
     */
    public void register(String sessionId, String replyId, Duration ttl) {
        if (sessionId == null || replyId == null || replyId.isBlank()) {
            return;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                 INSERT INTO turn_intent
                     (session_id, reply_id, state, error_text, created_at, expires_at)
                 VALUES (?, ?, 'QUEUED', NULL, NOW(3), DATE_ADD(NOW(3), INTERVAL ? SECOND))
                 ON DUPLICATE KEY UPDATE
                     reply_id = VALUES(reply_id),
                     state = 'QUEUED',
                     error_text = NULL,
                     created_at = VALUES(created_at),
                     expires_at = VALUES(expires_at)
                 """)) {
            ps.setString(1, sessionId);
            ps.setString(2, replyId);
            ps.setInt(3, (int) Math.max(1, ttl.toSeconds()));
            ps.executeUpdate();
        } catch (Exception e) {
            // 意图是「可续传」的增强，不是正确性前提：失败仅告警，不阻断本轮对话
            log.warn("TurnIntentStore: register failed for sid={}, rid={}: {}",
                sessionId, replyId, e.getMessage());
        }
    }

    /**
     * 标记本轮失败（排队超时 / 建立阶段异常）。
     *
     * <p>只在行的 reply_id 与本轮一致时改写——若更新的 turn 已登记意图，旧请求的失败
     * 不得把它的状态覆盖掉。行不存在（如租约已到手、意图已清除后才失败）则插入一条
     * FAILED，让刷新后的客户端仍能看到失败原因。
     */
    public void fail(String sessionId, String replyId, String error, Duration ttl) {
        if (sessionId == null || replyId == null || replyId.isBlank()) {
            return;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                 INSERT INTO turn_intent
                     (session_id, reply_id, state, error_text, created_at, expires_at)
                 VALUES (?, ?, 'FAILED', ?, NOW(3), DATE_ADD(NOW(3), INTERVAL ? SECOND))
                 ON DUPLICATE KEY UPDATE
                     state = IF(reply_id = VALUES(reply_id), 'FAILED', state),
                     error_text = IF(reply_id = VALUES(reply_id), VALUES(error_text), error_text),
                     expires_at = IF(reply_id = VALUES(reply_id), VALUES(expires_at), expires_at)
                 """)) {
            ps.setString(1, sessionId);
            ps.setString(2, replyId);
            ps.setString(3, truncate(error));
            ps.setInt(4, (int) Math.max(1, ttl.toSeconds()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("TurnIntentStore: fail mark failed for sid={}, rid={}: {}",
                sessionId, replyId, e.getMessage());
        }
    }

    /** 清除本轮的意图（租约到手 / 正常放弃）；只删 reply_id 匹配的行。 */
    public void clear(String sessionId, String replyId) {
        if (sessionId == null || replyId == null || replyId.isBlank()) {
            return;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                 "DELETE FROM turn_intent WHERE session_id = ? AND reply_id = ?")) {
            ps.setString(1, sessionId);
            ps.setString(2, replyId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("TurnIntentStore: clear failed for sid={}, rid={}: {}",
                sessionId, replyId, e.getMessage());
        }
    }

    /**
     * 查询会话当前有效的意图（已过期的行视为不存在）。
     *
     * @return 意图；无行/已过期/存储不可用时 null（调用方退回事件与租约判定）
     */
    public Intent find(String sessionId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                 SELECT reply_id, state, error_text
                   FROM turn_intent
                  WHERE session_id = ? AND expires_at > NOW(3)
                 """)) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                var replyId = rs.getString("reply_id");
                var failed = STATE_FAILED.equalsIgnoreCase(rs.getString("state"));
                return new Intent(replyId, failed, failed ? rs.getString("error_text") : null);
            }
        } catch (Exception e) {
            log.warn("TurnIntentStore: find failed for sid={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 清理过期行（每日兜底；读路径已按 expires_at 过滤）。 */
    public int deleteExpired() {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM turn_intent WHERE expires_at < NOW(3)")) {
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("TurnIntentStore: deleteExpired failed: {}", e.getMessage());
            return 0;
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        var trimmed = error.trim();
        return trimmed.length() <= 1000 ? trimmed : trimmed.substring(0, 1000);
    }
}
