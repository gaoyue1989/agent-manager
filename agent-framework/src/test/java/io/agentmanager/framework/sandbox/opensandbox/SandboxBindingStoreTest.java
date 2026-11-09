package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * U1 {@code SandboxBindingStoreTest}（U1 分支枚举即 DoD，sandbox-pool-opensandbox-design §9.1）：
 * 占额 CAS + PENDING 不变量 + 懒剔除守卫/改绑/重算/清理的分支与 SQL 形状（mock JDBC，
 * 风格同 SessionUserStoreTest）。真库语义验证见同包 {@code SandboxBindingStoreMySqlIT}
 * （HITL_MYSQL_IT=1 门控，本地/CI 手动跑）。
 */
class SandboxBindingStoreTest {

    private DataSource dataSource;
    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private final List<String> sqls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            sqls.add(inv.getArgument(0, String.class));
            return ps;
        });
        when(ps.executeUpdate()).thenReturn(1);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.getAutoCommit()).thenReturn(true);
    }

    /** 最近一次 SQL */
    private String lastSql() {
        return sqls.get(sqls.size() - 1);
    }

    private void mockEmptySelect() throws SQLException {
        when(rs.next()).thenReturn(false);
    }

    // ---- ② 占额 CAS ----

    @Test
    void occupyQuotaFullRollsBackWithoutPendingRow() throws Exception {
        // CAS affected=0（count=quota）→ rollback、无 PENDING 行、返回 QUOTA_FULL
        when(ps.executeUpdate()).thenReturn(1, 0);   // INSERT IGNORE 命中，CAS 落空

        var result = new SandboxBindingStore(dataSource).occupyQuota("u1", "s1", 5);

        assertEquals(SandboxBindingStore.OccupyStatus.QUOTA_FULL, result.status());
        verify(conn).rollback();
        assertTrue(sqls.stream().noneMatch(s -> s.contains("INSERT INTO sandbox_binding")),
            "QUOTA_FULL 不得落 PENDING 行: " + sqls);
    }

    @Test
    void occupyQuotaSuccessInsertsPendingRowWithExpiresWindow() throws Exception {
        // CAS affected=1 → 同事务 INSERT PENDING（expires_at = NOW()+10min，终审 R2）
        when(ps.executeUpdate()).thenReturn(1, 1, 1);

        var result = new SandboxBindingStore(dataSource).occupyQuota("u1", "s1", 5);

        assertEquals(SandboxBindingStore.OccupyStatus.OCCUPIED, result.status());
        assertTrue(result.pendingSandboxId().startsWith("pending:"));
        var pendingInsert = sqls.stream().filter(s -> s.contains("INSERT INTO sandbox_binding"))
            .findFirst().orElseThrow();
        assertTrue(pendingInsert.contains("pending") == false
                && pendingInsert.contains("DATE_ADD(NOW(3), INTERVAL 10 MINUTE)"),
            "PENDING INSERT 应带 NOW()+10min 过期窗: " + pendingInsert);
        assertTrue(pendingInsert.contains("VALUES (?, ?, ?,"));
        verify(conn).commit();
    }

    @Test
    void occupyQuotaCaplessForZeroQuota() throws Exception {
        // 终审 R2：quota<=0 = 不限 → CAS 无 < quota 判满条件（纯记账），不变量仍成立
        when(ps.executeUpdate()).thenReturn(1, 1, 1);

        var result = new SandboxBindingStore(dataSource).occupyQuota("u1", "s1", 0);

        assertEquals(SandboxBindingStore.OccupyStatus.OCCUPIED, result.status());
        var casSql = sqls.stream().filter(s -> s.startsWith("UPDATE sandbox_user"))
            .findFirst().orElseThrow();
        assertFalse(casSql.contains("binding_count <"), "quota=0 不得带判满条件: " + casSql);
    }

    @Test
    void occupyQuotaDuplicateSessionReturnsDuplicateNotCompensation() throws Exception {
        // 终审 d10：INSERT PENDING 撞 uk_session → DUPLICATE_SESSION（回 ① 重试），不补偿
        when(ps.executeUpdate()).thenReturn(1, 1).thenThrow(
            new SQLException("Duplicate entry 's1' for key 'uk_session'", "23000", 1062));

        var result = new SandboxBindingStore(dataSource).occupyQuota("u1", "s1", 5);

        assertEquals(SandboxBindingStore.OccupyStatus.DUPLICATE_SESSION, result.status());
        verify(conn).rollback();
        assertTrue(sqls.stream().noneMatch(s -> s.contains("DELETE FROM sandbox_user")),
            "撞键分支不产生补偿 DELETE: " + sqls);
    }

    @Test
    void occupyQuotaInsertsIgnoreAnchorForFirstSeenUser() throws Exception {
        when(ps.executeUpdate()).thenReturn(1, 1, 1);
        new SandboxBindingStore(dataSource).occupyQuota("u1", "s1", 5);
        assertTrue(sqls.get(0).startsWith("INSERT IGNORE INTO sandbox_user"),
            "首见用户先 INSERT IGNORE 锚行: " + sqls.get(0));
        var cas = sqls.get(1);
        assertTrue(cas.contains("binding_count = binding_count + 1")
            && cas.contains("binding_count < ?"), "CAS 条件: " + cas);
    }

    // ---- ③ 转正 / 补偿 ----

    @Test
    void promotePendingUpdatesToRealIdAndServerExpiresAt() throws Exception {
        boolean ok = new SandboxBindingStore(dataSource)
            .promotePending("pending:u1", "sbx-real", "2026-11-08T01:00:00Z");
        assertTrue(ok);
        var sql = lastSql();
        assertTrue(sql.contains("UPDATE sandbox_binding") && sql.contains("sandbox_id = ?")
            && sql.contains("LIKE 'pending:%'"), "转正 SQL: " + sql);
        var tsCap = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(org.mockito.ArgumentMatchers.eq(1), tsCap.capture());
        assertEquals("sbx-real", tsCap.getValue());
    }

    @Test
    void compensatePendingDeletesByOwnPendingIdOnly() throws Exception {
        // 终审 d10 定位纪律：补偿一律按自身 pending:{uuid}（+user_key）定位
        when(rs.next()).thenReturn(true, false).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("u1");

        new SandboxBindingStore(dataSource).compensatePending("pending:abc", "u1");

        var del = sqls.stream().filter(s -> s.startsWith("DELETE FROM sandbox_binding"))
            .findFirst().orElseThrow();
        assertTrue(del.contains("sandbox_id = ?") && del.contains("user_key = ?")
            && del.contains("LIKE 'pending:%'"), "补偿 DELETE SQL: " + del);
        var dec = sqls.stream().filter(s -> s.startsWith("UPDATE sandbox_user"))
            .findFirst().orElseThrow();
        assertTrue(dec.contains("binding_count = binding_count - 1"), "删中同事务计数递减: " + dec);
    }

    // ---- ① touch / 懒剔除 ----

    @Test
    void touchOnReuseOnlyUpdatesUpdatedAt() throws Exception {
        // 终审 R2：touch 只写 updated_at=NOW(3)（expires_at 由 renew 响应回填）
        assertTrue(new SandboxBindingStore(dataSource).touchOnReuse(7L, "s1"));
        var sql = lastSql();
        assertTrue(sql.contains("SET updated_at = NOW(3)"), sql);
        assertFalse(sql.toUpperCase().contains("EXPIRES_AT"), "touch 不动 expires_at: " + sql);
        assertTrue(sql.contains("AND session_id = ?"));
    }

    @Test
    void deleteByIdGuardedTripleCondition() throws Exception {
        // ⑤a probe 404：守卫 id + session_id + updated_at 未变
        when(rs.next()).thenReturn(true, false).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("u1");

        assertTrue(new SandboxBindingStore(dataSource).deleteByIdGuarded(9L, "s1", "2026-11-08 00:00:00.000"));
        var del = sqls.stream().filter(s -> s.startsWith("DELETE FROM sandbox_binding"))
            .findFirst().orElseThrow();
        assertTrue(del.contains("id = ?") && del.contains("session_id = ?") && del.contains("updated_at = ?"),
            "守卫 DELETE: " + del);
    }

    @Test
    void deleteByIdGuardedMissSkipsCountDecrement() throws Exception {
        // 守卫不命中（已被他副本改绑）→ affected=0 跳过、计数不动
        when(rs.next()).thenReturn(true, false).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("u1");
        when(ps.executeUpdate()).thenReturn(0);   // DELETE 落空

        assertFalse(new SandboxBindingStore(dataSource).deleteByIdGuarded(9L, "s1", "ts"));
        assertTrue(sqls.stream().noneMatch(s -> s.startsWith("UPDATE sandbox_user")));
        verify(conn).commit();   // 守卫未命中：空事务提交（无计数递减）
    }

    @Test
    void lazyDeleteExpiredExcludesPendingRows() throws Exception {
        mockEmptySelect();
        new SandboxBindingStore(dataSource).lazyDeleteExpired("u1");
        var select = sqls.get(0);
        assertTrue(select.startsWith("SELECT id FROM sandbox_binding")
            && select.contains("expires_at < NOW(3)")
            && select.contains("NOT LIKE 'pending:%'"), "候选查询: " + select);
    }

    @Test
    void recycleStalePendingTargetsAgedPendingOnly() throws Exception {
        mockEmptySelect();
        new SandboxBindingStore(dataSource).recycleStalePending("u1");
        var select = sqls.get(0);
        assertTrue(select.contains("LIKE 'pending:%'")
            && select.contains("created_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE)"),
            "陈旧 PENDING 回收（>10min）: " + select);
    }

    // ---- ⑤b 重算 / ⑤c 改绑 ----

    @Test
    void recomputeCountFirstStatementIsAnchorForUpdate() throws Exception {
        // 终审 R2：事务第一条语句必须是锚行 FOR UPDATE（当前读不建 MVCC 快照）
        when(rs.next()).thenReturn(true, true, true);
        when(rs.getInt(1)).thenReturn(4);

        int count = new SandboxBindingStore(dataSource).recomputeCount("u1");

        assertEquals(4, count);
        assertEquals("SELECT binding_count FROM sandbox_user WHERE user_key = ? FOR UPDATE", sqls.get(0));
        var recompute = sqls.get(1);
        assertTrue(recompute.contains("binding_count = (SELECT COUNT(*) FROM sandbox_binding")
            && recompute.contains("UPDATE sandbox_user"), "绝对覆写重算: " + recompute);
        verify(conn).commit();
    }

    @Test
    void rebindCarriesUpdatedAtSnapshotOptimisticCheck() throws Exception {
        // 终审 d2：改绑条件含 updated_at = 快照值（与在途 touch/P3 占位写互斥）
        assertTrue(new SandboxBindingStore(dataSource).rebind(3L, "old-sid", "new-sid", "2026-11-08 00:00:00.123"));
        var sql = lastSql();
        assertTrue(sql.contains("SET session_id = ?") && sql.contains("updated_at = NOW(3)")
            && sql.contains("AND session_id = ?") && sql.contains("AND updated_at = ?"), "改绑 SQL: " + sql);
    }

    // ---- P3 校验+占位 / 终刷 / 心跳 ----

    @Test
    void occupyWriteSingleConditionalUpdate() throws Exception {
        // 终审 d1/cold-R1-1：UPDATE SET updated_at=NOW(3) WHERE sandbox_id AND session_id
        assertTrue(new SandboxBindingStore(dataSource).occupyWrite("sbx-1", List.of("s1")));
        var sql = lastSql();
        assertTrue(sql.startsWith("UPDATE sandbox_binding SET updated_at = NOW(3)")
            && sql.contains("WHERE sandbox_id = ?") && sql.contains("session_id IN (?)"), sql);
    }

    @Test
    void touchExpiresAtNormalizesServerIsoTimestamp() throws Exception {
        // 服务端绝对 expiresAt（ISO 带 Z）→ UTC 墙钟字面量回填
        assertTrue(new SandboxBindingStore(dataSource).touchExpiresAt("sbx-1", "2026-11-08T01:00:00Z"));
        var cap = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(org.mockito.ArgumentMatchers.eq(1), cap.capture());
        assertEquals("2026-11-08 01:00:00.000", cap.getValue());
        assertTrue(lastSql().contains("expires_at = ?") && lastSql().contains("updated_at = NOW(3)"));
    }

    @Test
    void heartbeatTouchesUpdatedAtOnly() throws Exception {
        // 终审 R1：renew 失败的心跳写（跨副本保守标记）只刷 updated_at
        new SandboxBindingStore(dataSource).heartbeat("sbx-1");
        var sql = lastSql();
        assertTrue(sql.contains("SET updated_at = NOW(3)")
            && !sql.toUpperCase().contains("EXPIRES_AT ="), sql);
    }

    // ---- §4.1.2 定时清理 ----

    @Test
    void cleanupExpiredRecomputesUnderAnchorLock() throws Exception {
        // 终审 R1：清理的计数重算在锚行 FOR UPDATE 锁内（与 ② CAS 并发不低计）
        when(rs.next()).thenReturn(true, false)      // DISTINCT user_key 候选
            .thenReturn(true, false)                  // 锚行 FOR UPDATE
            .thenReturn(true, false);                 // 重算读
        when(rs.getString(1)).thenReturn("u1");
        when(rs.getInt(1)).thenReturn(2);
        when(ps.executeUpdate()).thenReturn(3);       // DELETE 行数

        int deleted = new SandboxBindingStore(dataSource)
            .cleanupExpired(Instant.parse("2026-11-01T00:00:00Z"));

        assertEquals(3, deleted);
        int anchorIdx = -1;
        for (int i = 0; i < sqls.size(); i++) {
            if (sqls.get(i).contains("FOR UPDATE")) {
                anchorIdx = i;
            }
        }
        int recomputeIdx = -1;
        for (int i = 0; i < sqls.size(); i++) {
            if (sqls.get(i).startsWith("UPDATE sandbox_user SET binding_count")) {
                recomputeIdx = i;
            }
        }
        assertTrue(anchorIdx >= 0 && recomputeIdx > anchorIdx,
            "重算必须在锚行锁语句之后: " + sqls);
    }

    @Test
    void cleanupEmptyAnchorsDeletesOnlyChildlessAgedRows() throws Exception {
        new SandboxBindingStore(dataSource).cleanupEmptyAnchors(Instant.parse("2026-11-01T00:00:00Z"));
        var sql = lastSql();
        assertTrue(sql.startsWith("DELETE FROM sandbox_user")
            && sql.contains("NOT EXISTS (SELECT 1 FROM sandbox_binding"), "空锚行清理: " + sql);
    }

    // ---- 归一化 ----

    @Test
    void normalizeTimestampHandlesIsoVariants() {
        assertEquals("2026-11-08 01:00:00.000",
            SandboxBindingStore.normalizeTimestamp("2026-11-08T01:00:00Z"));
        assertEquals("2026-11-08 09:00:00.000",
            SandboxBindingStore.normalizeTimestamp("2026-11-08T17:00:00+08:00"));
        assertEquals("2026-11-08 01:00:00.123",
            SandboxBindingStore.normalizeTimestamp("2026-11-08T01:00:00.123"));
        assertEquals("2026-11-08 01:00:00.123", SandboxBindingStore.normalizeTimestamp("2026-11-08 01:00:00.123"));
        assertEquals("garbage", SandboxBindingStore.normalizeTimestamp("garbage"));
    }

    @Test
    void storeFailureSurfacesAsDataAccessException() throws Exception {
        when(conn.prepareStatement(anyString())).thenThrow(new SQLException("db down"));
        assertThrows(SandboxBindingStore.DataAccessException.class,
            () -> new SandboxBindingStore(dataSource).touchOnReuse(1L, "s1"));
    }

    // ---- 行形态查询（listLive/listAll/lazyDeleteOwned）----

    @Test
    void listLiveExcludesPendingAndExpiredWithDbClockFreshness() throws Exception {
        mockRowResultSet();
        var rows = new SandboxBindingStore(dataSource).listLive("u1", 180);

        assertEquals(1, rows.size());
        assertEquals("sbx-1", rows.get(0).sandboxId());
        assertTrue(rows.get(0).fresh());
        var sql = sqls.get(0);
        assertTrue(sql.startsWith("SELECT id, user_key, session_id, sandbox_id, expires_at, updated_at, ")
            && sql.contains("(updated_at >= DATE_SUB(NOW(3), INTERVAL ? SECOND)) AS fresh")
            && sql.contains("AND expires_at >= NOW(3)")
            && sql.contains("AND sandbox_id NOT LIKE 'pending:%'")
            && sql.contains("ORDER BY expires_at ASC"), "listLive SQL（行龄过滤+DB 时钟 fresh）: " + sql);
        verify(ps).setInt(1, 180);
    }

    @Test
    void listAllJoinsTurnLeaseAndLimitsRows() throws Exception {
        mockRowResultSet();
        var rows = new SandboxBindingStore(dataSource).listAll(120, 200);

        assertEquals(1, rows.size());
        var sql = sqls.get(0);
        assertTrue(sql.contains("LEFT JOIN turn_lease t ON t.session_id = b.session_id AND t.expires_at > NOW(3)")
            && sql.contains("LIMIT 200"), "debug 绑定表（turn_lease JOIN + 上限）: " + sql);
    }

    @Test
    void lazyDeleteOwnedDeletesBySandboxAndSession() throws Exception {
        when(rs.next()).thenReturn(true, false).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("u1");

        assertTrue(new SandboxBindingStore(dataSource).lazyDeleteOwned("sbx-own", "s1"));
        var del = sqls.stream().filter(x -> x.startsWith("DELETE FROM sandbox_binding"))
            .findFirst().orElseThrow();
        assertTrue(del.contains("sandbox_id = ?") && del.contains("session_id = ?")
            && del.contains("NOT LIKE 'pending:%'"), "① probe 404 懒剔除 SQL: " + del);
    }

    /** listLive/listAll 的行结果集桩（id/user_key/.../fresh） */
    private void mockRowResultSet() throws SQLException {
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("user_key")).thenReturn("u1");
        when(rs.getString("session_id")).thenReturn("s1");
        when(rs.getString("sandbox_id")).thenReturn("sbx-1");
        when(rs.<java.time.LocalDateTime>getObject(eq("expires_at"), eq(java.time.LocalDateTime.class))).thenReturn(java.time.LocalDateTime.parse("2026-11-08T01:00:00.123"));
        when(rs.<java.time.LocalDateTime>getObject(eq("updated_at"), eq(java.time.LocalDateTime.class))).thenReturn(java.time.LocalDateTime.parse("2026-11-08T00:00:00.000"));
        when(rs.getBoolean("fresh")).thenReturn(true);
    }

    // ---- 重算/清理的边界分支（无锚行/异常回滚，真库 IT 的 mock 补集）----

    @Test
    void recomputeCountWithoutAnchorReturnsZeroAndCommits() throws Exception {
        // 无锚行（不该发生）→ 重算即 0，提交空事务
        when(rs.next()).thenReturn(false);
        assertEquals(0, new SandboxBindingStore(dataSource).recomputeCount("u-none"));
        verify(conn).commit();
        assertTrue(sqls.get(0).contains("FOR UPDATE"), "首语句仍为锚行 FOR UPDATE");
    }

    @Test
    void recomputeCountSqlFailureRollsBackAndWraps() throws Exception {
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            sqls.add(inv.getArgument(0, String.class));
            if (sqls.size() == 1) {
                return ps;   // 锚行 FOR UPDATE 成功
            }
            throw new SQLException("lock timeout");   // 后续语句失败
        });
        when(rs.next()).thenReturn(true);

        assertThrows(SandboxBindingStore.DataAccessException.class,
            () -> new SandboxBindingStore(dataSource).recomputeCount("u1"));
        verify(conn).rollback();
    }

    @Test
    void cleanupUserExpiredWithoutAnchorSkipsRowDelete() throws Exception {
        // 候选查询给出用户、锚行缺失（并发被清）→ 该用户跳过（commit 返回 0，不删行）
        when(rs.next()).thenReturn(true, false)   // DISTINCT user_key → 1 个用户
            .thenReturn(false);                    // 该用户锚行 FOR UPDATE → 空
        when(rs.getString(1)).thenReturn("u-gone");

        assertEquals(0, new SandboxBindingStore(dataSource)
            .cleanupExpired(Instant.parse("2026-11-01T00:00:00Z")));
        assertTrue(sqls.stream().noneMatch(x -> x.startsWith("DELETE FROM sandbox_binding")));
        verify(conn, org.mockito.Mockito.atLeastOnce()).commit();
    }

    @Test
    void cleanupExpiredSqlFailureSurfacesAsDataAccessException() throws Exception {
        when(ps.executeUpdate()).thenReturn(1);
        when(rs.next()).thenThrow(new SQLException("db down"));

        assertThrows(SandboxBindingStore.DataAccessException.class,
            () -> new SandboxBindingStore(dataSource).cleanupExpired(Instant.parse("2026-11-01T00:00:00Z")));
    }

    @Test
    void deleteGuardedBatchDeletesEachCandidateAndCounts() throws Exception {
        // 两行候选均守卫命中 → 计数递减两次（每删一行同事务 -1，不变量）
        when(rs.next()).thenReturn(true, true, false, true, true);   // 候选×(T,T,F) + 行1 SELECT(T) + 行2 SELECT(T)
        when(rs.getLong(1)).thenReturn(11L, 12L);
        when(rs.getString(1)).thenReturn("u1");
        when(ps.executeUpdate()).thenReturn(1);

        int deleted = new SandboxBindingStore(dataSource).lazyDeleteExpired("u1");
        assertEquals(2, deleted);
        long decCount = sqls.stream().filter(x -> x.contains("binding_count = binding_count - 1")).count();
        assertEquals(2, decCount);
    }

    @Test
    void deleteGuardedBatchGuardMissDoesNotCount() throws Exception {
        // 候选 2 行、第二行守卫不命中（并发改绑）→ 只删 1
        when(rs.next()).thenReturn(true, true, false, true, false);  // 候选×(T,T,F) + 行1 SELECT(T) + 行2 SELECT(F=守卫不命中前置读空)
        when(rs.getLong(1)).thenReturn(11L, 12L);
        when(rs.getString(1)).thenReturn("u1");
        when(ps.executeUpdate()).thenReturn(1, 0);

        assertEquals(1, new SandboxBindingStore(dataSource).lazyDeleteExpired("u1"));
    }
}
