package io.agentmanager.framework.sandbox.opensandbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * sandbox_user / sandbox_binding 簿记存储（sandbox-pool-opensandbox-design §4.1/§4.2）。
 *
 * <p>事务纪律（全部为毫秒级短事务，任何锁内不做 HTTP）：
 * <ul>
 *   <li><b>占额 CAS</b>：{@code UPDATE sandbox_user SET binding_count=binding_count+1
 *       WHERE user_key=? [AND binding_count < quota]}，affected=1 即占额成功；
 *       同事务 INSERT PENDING 绑定行——占额与占位原子，杜绝幽灵占额（§4.1 不变量：
 *       任意时刻 binding_count == COUNT(binding 行)）。</li>
 *   <li><b>懒剔除全部守卫式</b>（防并发误删）：每条 DELETE 带乐观条件，
 *       删中一行才同事务 binding_count-1，计数恒等于行数。</li>
 *   <li><b>⑤b 重算</b>：锚行 {@code SELECT ... FOR UPDATE} 为事务第一条语句
 *       （当前读不建 MVCC 快照，规避终审 R2 指出的旧快照低计→配额突破），
 *       锁内 {@code binding_count := COUNT(存活行)} 绝对覆写，与 ② CAS/定时清理
 *       同经锚行锁串行化（终审 R1）。</li>
 *   <li><b>⑤c LRU 改绑</b>：{@code updated_at = :快照值} 乐观校验，与受害会话在途
 *       ① touch / P3 占位写构成行锁互斥（终审 d2：任一提交序恰有一方 affected=1）。</li>
 * </ul>
 *
 * <p>PENDING 行标识：{@code sandbox_id LIKE 'pending:%'}——① 查找/victim 选取/过期行
 * 删除一律排除，陈旧 PENDING 由独立行龄回收（created_at &lt; NOW()-10min，终审 R2）。
 */
public class SandboxBindingStore {

    private static final Logger log = LoggerFactory.getLogger(SandboxBindingStore.class);

    /** PENDING 占位行的 sandbox_id 前缀（§4.1 不变量） */
    public static final String PENDING_PREFIX = "pending:";
    /** PENDING 行 expires_at 兜底窗（= 陈旧 PENDING 回收窗，§4.2 ②，终审 R2 钉死取值） */
    public static final long PENDING_EXPIRES_WINDOW_MINUTES = 10;

    private static final DateTimeFormatter SQL_DT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final DataSource dataSource;

    public SandboxBindingStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ---- 行形态 ----

    /**
     * 绑定行快照（listLive 的 fresh 标志由 SQL 侧以 DB 时钟判定，无副本时钟偏移）。
     * {@code updatedAt} 的字符串形态用于 ⑤c 乐观校验（DATETIME(3) 毫秒精度，
     * 同秒并发 touch 可分辨——终审 d2 依赖 updated_at 变化）。
     */
    public record BindingRow(long id, String userKey, String sessionId, String sandboxId,
                             String expiresAt, String updatedAt, boolean fresh) {
        public boolean isPending() {
            return sandboxId != null && sandboxId.startsWith(PENDING_PREFIX);
        }
    }

    /** 占额结果（② 分支枚举） */
    public enum OccupyStatus { OCCUPIED, QUOTA_FULL, DUPLICATE_SESSION }

    /** 占额成功返回自身 PENDING 行的 sandbox_id（pending:{uuid}，补偿按此定位，终审 d10） */
    public record OccupyResult(OccupyStatus status, String pendingSandboxId) {}

    // ---- ① 复用路径 ----

    /** 按会话查绑定行（排除 PENDING 行——PENDING 是本会话在途占位，不构成可复用沙箱，§4.2 ①） */
    public Optional<BindingRow> findBySessionId(String sessionId) {
        String sql = "SELECT id, user_key, session_id, sandbox_id, expires_at, updated_at "
            + "FROM sandbox_binding WHERE session_id = ? AND sandbox_id NOT LIKE 'pending:%' LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return Optional.ofNullable(rs.next() ? rowOf(rs) : null);
            }
        } catch (SQLException e) {
            throw new DataAccessException("findBySessionId failed: " + e.getMessage(), e);
        }
    }

    /**
     * ① touch 先行（终审 d2/cold-R1-1）：在 probe/connect 之前占住 updated_at（毫秒精度），
     * 封「acquire 后、守护线程首拍续期前」的 grace 空窗。只写 updated_at 不动 expires_at
     * （终审 R2：此刻不存在服务端响应可回填，expires_at 由 renew 响应/T2 终刷回填）。
     * affected=0 → 绑定已被他副本改绑或懒剔除，调用方放弃复用视同 miss。
     */
    public boolean touchOnReuse(long id, String sessionId) {
        String sql = "UPDATE sandbox_binding SET updated_at = NOW(3) "
            + "WHERE id = ? AND session_id = ? AND sandbox_id NOT LIKE 'pending:%'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, sessionId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DataAccessException("touchOnReuse failed: " + e.getMessage(), e);
        }
    }

    /** ① probe 404 后懒剔除自己名下绑定（行归属自己，无并发争用；同事务计数递减） */
    public boolean lazyDeleteOwned(String sandboxId, String sessionId) {
        String cond = "sandbox_id = ? AND session_id = ? AND sandbox_id NOT LIKE 'pending:%'";
        try {
            return callRowDelete(cond, ps -> {
                ps.setString(1, sandboxId);
                ps.setString(2, sessionId);
            }) != null;
        } catch (SQLException e) {
            throw new DataAccessException("lazyDeleteOwned failed: " + e.getMessage(), e);
        }
    }

    // ---- ② 占额（CAS + PENDING 占位，同事务）----

    /**
     * 占额短事务：INSERT IGNORE 锚行 → 条件 CAS 增计数 → INSERT PENDING 行。
     *
     * <p>quota &lt;= 0 = 不限（§4.7）：跳过 {@code binding_count < quota} 判满条件、
     * 纯增计数仍记账——不变量「计数==行数」在任意 quota 取值下均成立（终审 R2）。
     * INSERT PENDING 撞 uk_session（同会话/同用户 A2A 获取在途）→ 返回
     * DUPLICATE_SESSION，调用方回 ① 重试而非补偿（终审 d10：补偿 DELETE 一律按
     * 自身 pending:{uuid} 定位，本方法的 INSERT 失败没有已落行，CAS 增量随之回滚）。
     */
    public OccupyResult occupyQuota(String userKey, String sessionId, int quota) {
        String pendingSandboxId = PENDING_PREFIX + java.util.UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 锚行兜底存在（首见用户）；INSERT IGNORE 对已存在行幂等
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT IGNORE INTO sandbox_user (user_key) VALUES (?)")) {
                    ps.setString(1, userKey);
                    ps.executeUpdate();
                }
                // 占额 CAS：quota<=0 不判满（仅记账），否则带上限条件
                int affected;
                String casSql = quota <= 0
                    ? "UPDATE sandbox_user SET binding_count = binding_count + 1 WHERE user_key = ?"
                    : "UPDATE sandbox_user SET binding_count = binding_count + 1 "
                        + "WHERE user_key = ? AND binding_count < ?";
                try (PreparedStatement ps = conn.prepareStatement(casSql)) {
                    ps.setString(1, userKey);
                    if (quota > 0) {
                        ps.setInt(2, quota);
                    }
                    affected = ps.executeUpdate();
                }
                if (affected == 0) {
                    conn.rollback();   // 未占额，无补偿需要
                    return new OccupyResult(OccupyStatus.QUOTA_FULL, null);
                }
                // PENDING 占位：expires_at 固定 NOW()+10min（终审 R2：显式带上，豁免 ⑤a 过期行删除）
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sandbox_binding (user_key, session_id, sandbox_id, expires_at) "
                            + "VALUES (?, ?, ?, DATE_ADD(NOW(3), INTERVAL "
                            + PENDING_EXPIRES_WINDOW_MINUTES + " MINUTE))")) {
                    ps.setString(1, userKey);
                    ps.setString(2, sessionId);
                    ps.setString(3, pendingSandboxId);
                    ps.executeUpdate();
                }
                conn.commit();
                return new OccupyResult(OccupyStatus.OCCUPIED, pendingSandboxId);
            } catch (SQLException e) {
                conn.rollback();
                // 撞 uk_session：同会话（或同用户 A2A 共享键）获取在途 → 回 ① 重试判定（终审 d10）
                if (isDuplicateKey(e)) {
                    return new OccupyResult(OccupyStatus.DUPLICATE_SESSION, null);
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("occupyQuota failed: " + e.getMessage(), e);
        }
    }

    /**
     * ③ 转正：PENDING 行 UPDATE 为真实 sandbox_id + 服务端绝对 expiresAt。
     * affected=0（行已被并发误删/⑤a 改绑，终审 R1/d8）→ 返回 false 仅告警放弃：
     * 计数不动（删方已递减），孤儿沙箱靠服务端 TTL GC，不重复递减破坏不变量。
     */
    public boolean promotePending(String pendingSandboxId, String realSandboxId, String serverExpiresAt) {
        String sql = "UPDATE sandbox_binding SET sandbox_id = ?, expires_at = ?, updated_at = NOW(3) "
            + "WHERE sandbox_id = ? AND sandbox_id LIKE 'pending:%'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, realSandboxId);
            ps.setString(2, normalizeTimestamp(serverExpiresAt));
            ps.setString(3, pendingSandboxId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DataAccessException("promotePending failed: " + e.getMessage(), e);
        }
    }

    /** ③ 失败补偿：按自身 PENDING 行 sandbox_id 定位删除 + 计数递减（终审 d10 定位纪律） */
    public void compensatePending(String pendingSandboxId, String userKey) {
        String cond = "sandbox_id = ? AND user_key = ? AND sandbox_id LIKE 'pending:%'";
        try {
            callRowDelete(cond, ps -> {
                ps.setString(1, pendingSandboxId);
                ps.setString(2, userKey);
            });
        } catch (SQLException e) {
            throw new DataAccessException("compensatePending failed: " + e.getMessage(), e);
        }
    }

    // ---- ⑤a 懒剔除（守卫式）----

    /** 过期行删除（expires_at 为服务端绝对时间缓存；排除 PENDING——陈旧 PENDING 有独立回收） */
    public int lazyDeleteExpired(String userKey) {
        return deleteGuardedBatch(userKey,
            "id = ? AND user_key = ? AND expires_at < NOW(3) AND sandbox_id NOT LIKE 'pending:%'",
            "user_key = ? AND expires_at < NOW(3) AND sandbox_id NOT LIKE 'pending:%'");
    }

    /** 陈旧 PENDING 回收（created_at < NOW()-10min；create HTTP 进行中的新 PENDING 不受影响） */
    public int recycleStalePending(String userKey) {
        return deleteGuardedBatch(userKey,
            "id = ? AND user_key = ? AND sandbox_id LIKE 'pending:%' AND created_at < DATE_SUB(NOW(3), INTERVAL "
                + PENDING_EXPIRES_WINDOW_MINUTES + " MINUTE)",
            "user_key = ? AND sandbox_id LIKE 'pending:%' AND created_at < DATE_SUB(NOW(3), INTERVAL "
                + PENDING_EXPIRES_WINDOW_MINUTES + " MINUTE)");
    }

    /**
     * probe 404 行的守卫删除（⑤a，probe 在锚锁外做）：{@code id + session_id + updated_at 未变}
     * 三重条件——已被他副本改绑/在途 touch（updated_at 已变）则 affected=0 跳过；删中同事务计数递减。
     */
    public boolean deleteByIdGuarded(long id, String expectedSessionId, String expectedUpdatedAt) {
        String cond = "id = ? AND session_id = ? AND updated_at = ?";
        try {
            return callRowDelete(cond, ps -> {
                ps.setLong(1, id);
                ps.setString(2, expectedSessionId);
                ps.setString(3, expectedUpdatedAt);
            }) != null;
        } catch (SQLException e) {
            throw new DataAccessException("deleteByIdGuarded failed: " + e.getMessage(), e);
        }
    }

    // ---- ⑤b / ⑤c ----

    /**
     * ⑤b 重算（锚行锁内绝对覆写）：事务第一条语句必须是锚行 FOR UPDATE（当前读不建
     * MVCC 快照）——锁内 COUNT 见到的提交态与锁序一致，与并发 ② CAS/定时清理互斥。
     * 在途 PENDING 行计入 COUNT（不吞额）。返回重算后的计数供调用方决定是否回 ② 重试 CAS。
     */
    public int recomputeCount(String userKey) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int count;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT binding_count FROM sandbox_user WHERE user_key = ? FOR UPDATE")) {
                    ps.setString(1, userKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.commit();
                            return 0;   // 无锚行=无绑定，重算即 0
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE sandbox_user SET binding_count = "
                            + "(SELECT COUNT(*) FROM sandbox_binding WHERE user_key = ?) "
                            + "WHERE user_key = ?")) {
                    ps.setString(1, userKey);
                    ps.setString(2, userKey);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT binding_count FROM sandbox_user WHERE user_key = ?")) {
                    ps.setString(1, userKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        count = rs.next() ? rs.getInt(1) : 0;
                    }
                }
                conn.commit();
                return count;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("recomputeCount failed: " + e.getMessage(), e);
        }
    }

    /**
     * 重读 live（⑤c + 预检共用，读侧行龄过滤）：排除 PENDING 行与 expires_at 已过期行
     * （§4.3 预检假 409 修正）；fresh = updated_at ≥ NOW(3)-grace（DB 时钟判定，双事实源第二源）。
     */
    public List<BindingRow> listLive(String userKey, int graceSeconds) {
        String sql = "SELECT id, user_key, session_id, sandbox_id, expires_at, updated_at, "
            + "(updated_at >= DATE_SUB(NOW(3), INTERVAL ? SECOND)) AS fresh "
            + "FROM sandbox_binding WHERE user_key = ? AND expires_at >= NOW(3) "
            + "AND sandbox_id NOT LIKE 'pending:%' ORDER BY expires_at ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, graceSeconds);
            ps.setString(2, userKey);
            List<BindingRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rowOf(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new DataAccessException("listLive failed: " + e.getMessage(), e);
        }
    }

    /**
     * ⑤c LRU 改绑（锚锁外、updated_at 快照乐观校验）：victim 会话在途 ① touch /
     * P3 占位写一旦先提交，updated_at 已变 → affected=0（终审 d2 互斥证明）。
     */
    public boolean rebind(long id, String fromSessionId, String toSessionId, String expectedUpdatedAt) {
        String sql = "UPDATE sandbox_binding SET session_id = ?, updated_at = NOW(3) "
            + "WHERE id = ? AND session_id = ? AND updated_at = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toSessionId);
            ps.setLong(2, id);
            ps.setString(3, fromSessionId);
            ps.setString(4, expectedUpdatedAt);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DataAccessException("rebind failed: " + e.getMessage(), e);
        }
    }

    // ---- P3 校验+占位 / 续期回填 / debug ----

    /**
     * P3 resume / locate 直连的「归属校验+占位」合一条件写（终审 d1/cold-R1-1）：
     * {@code UPDATE ... SET updated_at=NOW(3) WHERE sandbox_id=? AND session_id=?}。
     * affected=1 → 归属有效且已在途占位；affected=0 → 无行/已改绑/slot↔binding 漂移
     * → 调用方 close 句柄 + 降级本决策。executorSessionIds 为候选会话键
     * （Channel=前端 sid；locate 链路含 USER(userId) 第二候选）。
     */
    public boolean occupyWrite(String sandboxId, List<String> executorSessionIds) {
        if (executorSessionIds == null || executorSessionIds.isEmpty()) {
            return false;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(executorSessionIds.size(), "?"));
        String sql = "UPDATE sandbox_binding SET updated_at = NOW(3) "
            + "WHERE sandbox_id = ? AND session_id IN (" + placeholders + ") "
            + "AND sandbox_id NOT LIKE 'pending:%'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sandboxId);
            for (int i = 0; i < executorSessionIds.size(); i++) {
                ps.setString(i + 2, executorSessionIds.get(i));
            }
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DataAccessException("occupyWrite failed: " + e.getMessage(), e);
        }
    }

    /** T2/T3 终刷：renew 响应的服务端绝对 expiresAt 回填（fresh 同步刷新，busy 判定第二事实源） */
    public boolean touchExpiresAt(String sandboxId, String serverExpiresAt) {
        String sql = "UPDATE sandbox_binding SET expires_at = ?, updated_at = NOW(3) "
            + "WHERE sandbox_id = ? AND sandbox_id NOT LIKE 'pending:%'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeTimestamp(serverExpiresAt));
            ps.setString(2, sandboxId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DataAccessException("touchExpiresAt failed: " + e.getMessage(), e);
        }
    }

    /**
     * renew 失败的心跳写（终审 R1：跨副本保守标记载体）——只刷 updated_at，
     * 使该绑定在他副本 ⑤c busy 判定中呈「新鲜」（暂不参与 LRU victim 选取），
     * 与「多副本协调只靠共享存储」纪律对齐。
     */
    public void heartbeat(String sandboxId) {
        String sql = "UPDATE sandbox_binding SET updated_at = NOW(3) "
            + "WHERE sandbox_id = ? AND sandbox_id NOT LIKE 'pending:%'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sandboxId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("heartbeat failed: " + e.getMessage(), e);
        }
    }

    /** debug 绑定表（§4.8）：跨副本聚合 + turn_running 派生（turn_lease JOIN + updated_at 新鲜度） */
    public List<BindingRow> listAll(int graceSeconds, int limit) {
        String sql = "SELECT b.id, b.user_key, b.session_id, b.sandbox_id, b.expires_at, b.updated_at, "
            + "(b.updated_at >= DATE_SUB(NOW(3), INTERVAL ? SECOND) OR t.session_id IS NOT NULL) AS fresh "
            + "FROM sandbox_binding b "
            + "LEFT JOIN turn_lease t ON t.session_id = b.session_id AND t.expires_at > NOW(3) "
            + "ORDER BY b.updated_at DESC LIMIT " + Math.max(1, limit);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, graceSeconds);
            List<BindingRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rowOf(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new DataAccessException("listAll failed: " + e.getMessage(), e);
        }
    }

    // ---- §4.1.2 定时清理 ----

    /**
     * 定时清理（SessionCleanupService 调用）：删除 expires_at 早于 cutoff 的绑定行并
     * 按用户重算计数。计数重算在<b>锚行 FOR UPDATE 锁内</b>执行（终审 R1：与 ② CAS
     * 并发时低计会瞬时突破配额）——锚行锁与 ② CAS/⑤b 重算同锁串行化。
     *
     * @return 删除的绑定行数
     */
    public int cleanupExpired(Instant cutoff) {
        String cutoffStr = SQL_DT.format(LocalDateTime.ofInstant(cutoff, java.time.ZoneId.systemDefault()));
        try {
            List<String> affectedUsers = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT user_key FROM sandbox_binding WHERE expires_at < ?")) {
                ps.setString(1, cutoffStr);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        affectedUsers.add(rs.getString(1));
                    }
                }
            }
            int deleted = 0;
            for (String userKey : affectedUsers) {
                deleted += cleanupUserExpired(userKey, cutoffStr);
            }
            return deleted;
        } catch (SQLException e) {
            throw new DataAccessException("cleanupExpired failed: " + e.getMessage(), e);
        }
    }

    /** 单用户清理事务：锚行 FOR UPDATE → 删过期行 → count := COUNT(存活行)（不变量保持） */
    private int cleanupUserExpired(String userKey, String cutoffStr) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int deleted;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT binding_count FROM sandbox_user WHERE user_key = ? FOR UPDATE")) {
                    ps.setString(1, userKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.commit();
                            return 0;   // 无锚行（不该发生）：跳过，不删行
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM sandbox_binding WHERE user_key = ? AND expires_at < ?")) {
                    ps.setString(1, userKey);
                    ps.setString(2, cutoffStr);
                    deleted = ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE sandbox_user SET binding_count = "
                            + "(SELECT COUNT(*) FROM sandbox_binding WHERE user_key = ?) WHERE user_key = ?")) {
                    ps.setString(1, userKey);
                    ps.setString(2, userKey);
                    ps.executeUpdate();
                }
                conn.commit();
                return deleted;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 锚表防膨胀：清理后已无绑定行且 updated_at 超保留窗口的 sandbox_user 行删除 */
    public int cleanupEmptyAnchors(Instant cutoff) {
        String cutoffStr = SQL_DT.format(LocalDateTime.ofInstant(cutoff, java.time.ZoneId.systemDefault()));
        String sql = "DELETE FROM sandbox_user WHERE updated_at < ? "
            + "AND NOT EXISTS (SELECT 1 FROM sandbox_binding b WHERE b.user_key = sandbox_user.user_key)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoffStr);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("cleanupEmptyAnchors failed: " + e.getMessage(), e);
        }
    }

    // ---- 内部工具 ----

    /** 守卫式批量删除：先查候选 id，逐行带守卫条件 DELETE + 计数递减（同一短事务） */
    private int deleteGuardedBatch(String userKey, String deleteCond, String selectCond) {
        try {
            List<Long> ids = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM sandbox_binding WHERE " + selectCond)) {
                ps.setString(1, userKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            int deleted = 0;
            for (Long id : ids) {
                long finalId = id;
                if (callRowDelete(deleteCond, ps -> {
                    ps.setLong(1, finalId);
                    ps.setString(2, userKey);
                }) != null) {
                    deleted++;
                }
            }
            return deleted;
        } catch (SQLException e) {
            throw new DataAccessException("deleteGuardedBatch failed: " + e.getMessage(), e);
        }
    }

    /**
     * 行删除短事务：SELECT user_key（与 DELETE 同形条件、同序参数）→ 守卫 DELETE →
     * 删中则锚行计数递减。返回删中行的 user_key（守卫不命中返回 null）。
     */
    private String callRowDelete(String whereCond, SqlConsumer1 binder) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String userKey;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT user_key FROM sandbox_binding WHERE " + whereCond)) {
                    binder.accept(ps);
                    try (ResultSet rs = ps.executeQuery()) {
                        userKey = rs.next() ? rs.getString(1) : null;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM sandbox_binding WHERE " + whereCond)) {
                    binder.accept(ps);
                    if (ps.executeUpdate() == 0) {
                        conn.commit();
                        return null;   // 守卫不命中：已被他副本改绑/删除，跳过
                    }
                }
                if (userKey != null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE sandbox_user SET binding_count = binding_count - 1 "
                                + "WHERE user_key = ? AND binding_count > 0")) {
                        ps.setString(1, userKey);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
                return userKey;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @FunctionalInterface
    private interface SqlConsumer1 {
        void accept(PreparedStatement ps) throws SQLException;
    }

    private BindingRow rowOf(ResultSet rs) throws SQLException {
        // fresh 列仅 listLive/listAll 的 SELECT 携带（findBySessionId 不取）；findColumn 兼容两种形态
        boolean fresh = hasColumn(rs, "fresh") && rs.getBoolean("fresh");
        return new BindingRow(
            rs.getLong("id"),
            rs.getString("user_key"),
            rs.getString("session_id"),
            rs.getString("sandbox_id"),
            sqlDatetime(rs.getObject("expires_at", LocalDateTime.class)),
            sqlDatetime(rs.getObject("updated_at", LocalDateTime.class)),
            fresh);
    }

    private static boolean hasColumn(ResultSet rs, String column) {
        try {
            rs.findColumn(column);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** LocalDateTime → MySQL 字面量（'T' 分隔符兼容性稳妥起见统一空格） */
    private static String sqlDatetime(LocalDateTime dt) {
        return dt == null ? null : SQL_DT.format(dt);
    }

    /**
     * 服务端绝对时间（SDK OffsetDateTime.toString()，如 {@code 2026-11-07T15:18:37Z}）
     * → UTC 墙钟 MySQL 字面量。DB 会话时区按容器默认 UTC 假设（oaf-mysql/e2e-mysql 均无
     * TZ 覆盖）——NOW(3)/DATE_ADD 与本格式同一时钟，⑤a 过期删除/预检行龄过滤比较才成立；
     * 解析失败原样返回（退化行为）。
     */
    static String normalizeTimestamp(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            if (raw.contains("T") && (raw.endsWith("Z")
                    || raw.matches(".*[+-]\\d{2}:?\\d{2}$"))) {
                return SQL_DT.format(java.time.OffsetDateTime.parse(raw)
                    .atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime());
            }
            if (raw.contains("T")) {
                return SQL_DT.format(LocalDateTime.parse(raw));
            }
            return raw;
        } catch (Exception e) {
            return raw;
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        // MySQL duplicate entry：SQLState 23000 / 厂商码 1062
        return "23000".equals(e.getSQLState()) || e.getErrorCode() == 1062;
    }

    /** 存储访问异常（调用方据此走 fail-open：直建不记账 + 60s 限流 WARN，§4.2） */
    public static class DataAccessException extends RuntimeException {
        public DataAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
