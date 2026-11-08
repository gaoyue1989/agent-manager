package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.mysql.cj.jdbc.MysqlDataSource;

/**
 * {@link SandboxBindingStore} 真库集成测试（真实 MySQL，U1 语义级验证）。
 *
 * <p>运行方式（与其它 MySQL IT 同款开关）：
 * <pre>
 * HITL_MYSQL_IT=1 CHECKPOINT_JDBC_URL=jdbc:mysql://127.0.0.1:13306/sandbox_pool_it \
 *   CHECKPOINT_USERNAME=e2e CHECKPOINT_PASSWORD=e2e-pass mvn test -Dtest=SandboxBindingStoreMySqlIT
 * </pre>
 *
 * <p>mock 单测覆盖分支与 SQL 形状；真库覆盖语义（占额 CAS 原子性、PENDING 不变量
 * 「计数==行数」、守卫式删除的乐观条件、updated_at 毫秒快照改绑互斥、锚行锁重算收敛
 * 幽灵占额、清理幂等）——这些恰是设计评审 P0/P1 修订（终审 R1/R2/d8/d10）的落点，
 * JDBC mock 无法证明。
 */
@EnabledIfEnvironmentVariable(named = "HITL_MYSQL_IT", matches = "1")
class SandboxBindingStoreMySqlIT {

    private DataSource dataSource() {
        var ds = new MysqlDataSource();
        ds.setURL(System.getenv("CHECKPOINT_JDBC_URL"));
        ds.setUser(System.getenv("CHECKPOINT_USERNAME"));
        ds.setPassword(System.getenv("CHECKPOINT_PASSWORD"));
        return ds;
    }

    private SandboxBindingStore store(DataSource ds) throws Exception {
        io.agentmanager.framework.support.TestSchemaMigrator.migrate(ds);   // 含 V15 两表
        return new SandboxBindingStore(ds);
    }

    private int scalar(DataSource ds, String sql) throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void exec(DataSource ds, String sql) throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private void cleanupUser(DataSource ds, String userKey) throws Exception {
        exec(ds, "DELETE FROM sandbox_binding WHERE user_key = '" + userKey + "'");
        exec(ds, "DELETE FROM sandbox_user WHERE user_key = '" + userKey + "'");
    }

    // ---- ② 占额 CAS + PENDING 不变量 ----

    @Test
    void occupyQuotaCasHonoursCapAndInsertsPending() throws Exception {
        var ds = dataSource();
        var store = store(ds);
        String user = "it-u-" + UUID.randomUUID();
        try {
            // count=quota-1 → 占额成功且同事务落 PENDING 行（不变量：计数==行数）
            var r1 = store.occupyQuota(user, "s1", 1);
            assertEquals(SandboxBindingStore.OccupyStatus.OCCUPIED, r1.status());
            assertEquals(1, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));
            assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM sandbox_binding WHERE user_key='" + user + "'"));
            assertTrue(scalar(ds, "SELECT COUNT(*) FROM sandbox_binding WHERE user_key='" + user
                + "' AND sandbox_id LIKE 'pending:%' AND expires_at > NOW(3)") == 1,
                "PENDING 行 expires_at 应为 NOW()+10min");

            // count=quota → CAS 落空：QUOTA_FULL 且无新行（无幽灵占额）
            var r2 = store.occupyQuota(user, "s2", 1);
            assertEquals(SandboxBindingStore.OccupyStatus.QUOTA_FULL, r2.status());
            assertEquals(1, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));

            // quota=0（不限）→ capless 记账仍成立
            var r3 = store.occupyQuota(user, "s3", 0);
            assertEquals(SandboxBindingStore.OccupyStatus.OCCUPIED, r3.status());
            assertEquals(2, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));
            assertEquals(2, scalar(ds, "SELECT COUNT(*) FROM sandbox_binding WHERE user_key='" + user + "'"));

            // 同会话并发占额（PENDING 行存在）→ 撞 uk_session → DUPLICATE_SESSION（终审 d10）
            var r4 = store.occupyQuota(user, "s1", 0);
            assertEquals(SandboxBindingStore.OccupyStatus.DUPLICATE_SESSION, r4.status());
            // 不变量保持（INSERT 失败的 CAS 增量随事务回滚）
            assertEquals(2, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));
            assertEquals(2, scalar(ds, "SELECT COUNT(*) FROM sandbox_binding WHERE user_key='" + user + "'"));
        } finally {
            cleanupUser(ds, user);
        }
    }

    // ---- ③ 转正 / 补偿 ----

    @Test
    void promoteAndCompensateKeepCountEqualsRows() throws Exception {
        var ds = dataSource();
        var store = store(ds);
        String user = "it-u-" + UUID.randomUUID();
        try {
            var occ = store.occupyQuota(user, "s1", 5);
            // 转正：PENDING 行 UPDATE 为真实 id + 服务端绝对 expiresAt（ISO 带 Z 归一化）
            assertTrue(store.promotePending(occ.pendingSandboxId(), "sbx-real-1", "2026-11-08T01:00:00Z"));
            assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM sandbox_binding WHERE user_key='" + user
                + "' AND sandbox_id='sbx-real-1' AND expires_at='2026-11-08 01:00:00.000'"));
            assertEquals(1, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));

            // 转正 affected=0（行已不在）→ false，计数不动（终审 R1/d8）
            assertFalse(store.promotePending("pending:ghost", "sbx-x", "2026-11-08T01:00:00Z"));

            // create 失败补偿：DELETE PENDING（按自身 sandbox_id 定位）+ 计数递减
            var occ2 = store.occupyQuota(user, "s2", 5);
            store.compensatePending(occ2.pendingSandboxId(), user);
            assertEquals(1, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));
            assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM sandbox_binding WHERE user_key='" + user + "'"));
        } finally {
            cleanupUser(ds, user);
        }
    }

    // ---- ⑤a 懒剔除（守卫式）----

    @Test
    void guardedDeletesHonourConditionsAndKeepInvariant() throws Exception {
        var ds = dataSource();
        var store = store(ds);
        String user = "it-u-" + UUID.randomUUID();
        try {
            // 行 A：过期行（expires_at 已过）→ lazyDeleteExpired 删
            var occA = store.occupyQuota(user, "sA", 5);
            store.promotePending(occA.pendingSandboxId(), "sbx-a", "2020-01-01T00:00:00Z");
            // 行 B：probe 404 行（守卫 updated_at 未变）
            var occB = store.occupyQuota(user, "sB", 5);
            store.promotePending(occB.pendingSandboxId(), "sbx-b", "2026-12-01T00:00:00Z");
            // 行 C：陈旧 PENDING（created_at 回拨 11min）
            var occC = store.occupyQuota(user, "sC", 5);
            exec(ds, "UPDATE sandbox_binding SET created_at = NOW(3) - INTERVAL 11 MINUTE WHERE sandbox_id='"
                + occC.pendingSandboxId() + "'");
            // 行 D：新 PENDING（create HTTP 进行中）→ 不回收
            var occD = store.occupyQuota(user, "sD", 5);
            assertEquals(4, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));

            // ⑤a：过期行删 + 陈旧 PENDING 回收、新 PENDING 保留
            assertEquals(1, store.lazyDeleteExpired(user));
            assertEquals(1, store.recycleStalePending(user));
            assertEquals(2, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));
            assertEquals(2, scalar(ds, "SELECT COUNT(*) FROM sandbox_binding WHERE user_key='" + user + "'"));
            assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM sandbox_binding WHERE sandbox_id='"
                + occD.pendingSandboxId() + "'"), "新 PENDING（<10min）不回收");

            // probe 404 守卫删除：updated_at 未变 → 删中；守卫不命中 → affected=0 跳过
            var rowB = store.findBySessionId("sB").orElseThrow();
            assertTrue(store.deleteByIdGuarded(rowB.id(), rowB.sessionId(), rowB.updatedAt()));
            assertEquals(1, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));

            var rowD = store.findBySessionId("sD");   // PENDING 行不被 findBySessionId 命中（① 忽略 PENDING）
            assertTrue(rowD.isEmpty());
            assertFalse(store.deleteByIdGuarded(999999L, "sX", "2000-01-01 00:00:00.000"));

            // probe 网络异常保守视为活：无删除入口被调（语义在 Manager 层，此处验证删除 API 不误删活行）
            assertEquals(1, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));
        } finally {
            cleanupUser(ds, user);
        }
    }

    // ---- ⑤b 重算（幽灵占额修正）----

    @Test
    void recomputeCountFixesGhostQuotaUnderAnchorLock() throws Exception {
        var ds = dataSource();
        var store = store(ds);
        String user = "it-u-" + UUID.randomUUID();
        try {
            var occ = store.occupyQuota(user, "s1", 5);
            store.promotePending(occ.pendingSandboxId(), "sbx-r", "2026-12-01T00:00:00Z");
            // 测试钩子：注入幽灵占额（binding_count 偏大，模拟占额后崩溃丢绑定行的残留态）
            exec(ds, "UPDATE sandbox_user SET binding_count = 9 WHERE user_key='" + user + "'");
            assertEquals(9, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));

            // L13（幽灵占额修正）：重算后计数 == 行数，配额恢复可判断
            assertEquals(1, store.recomputeCount(user));
            assertEquals(1, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));

            // 在途 PENDING 计入 COUNT（不吞额）
            var occ2 = store.occupyQuota(user, "s2", 2);   // count=1 < 2 → 占额成功
            assertEquals(SandboxBindingStore.OccupyStatus.OCCUPIED, occ2.status());
            assertEquals(2, store.recomputeCount(user));   // 真实行数 2（1 真实 + 1 PENDING）
        } finally {
            cleanupUser(ds, user);
        }
    }

    // ---- ⑤c 改绑乐观校验（touch/改绑互斥，终审 d2）----

    @Test
    void rebindOptimisticCheckMutuallyExclusiveWithTouch() throws Exception {
        var ds = dataSource();
        var store = store(ds);
        String user = "it-u-" + UUID.randomUUID();
        try {
            var occ = store.occupyQuota(user, "victim-sid", 5);
            store.promotePending(occ.pendingSandboxId(), "sbx-v", "2026-12-01T00:00:00Z");
            var victim = store.findBySessionId("victim-sid").orElseThrow();

            // 快照读后、改绑前：受害会话在途 ① touch 先提交（毫秒精度可分辨同秒内并发）
            Thread.sleep(2);
            assertTrue(store.touchOnReuse(victim.id(), "victim-sid"));
            var touched = store.findBySessionId("victim-sid").orElseThrow();
            assertNotEquals(victim.updatedAt(), touched.updatedAt(),
                "DATETIME(3) 毫秒精度：touch 后 updated_at 必变（乐观校验的判据）");

            // 改绑用旧快照 → affected=0（受害在途 touch 拦截，双用不发生）
            assertFalse(store.rebind(victim.id(), "victim-sid", "attacker-sid", victim.updatedAt()));
            // 改绑用新快照 → affected=1（正常 LRU 转移，计数不变）
            assertTrue(store.rebind(touched.id(), "victim-sid", "attacker-sid", touched.updatedAt()));
            var rebound = store.findBySessionId("attacker-sid").orElseThrow();
            assertEquals("sbx-v", rebound.sandboxId(), "LRU 转移只改归属，沙箱本体不变");
            assertEquals("attacker-sid", rebound.sessionId());
            assertTrue(store.findBySessionId("victim-sid").isEmpty());
            assertEquals(1, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user + "'"));
        } finally {
            cleanupUser(ds, user);
        }
    }

    // ---- P3 校验+占位 ----

    @Test
    void occupyWriteValidatesOwnershipAndOccupies() throws Exception {
        var ds = dataSource();
        var store = store(ds);
        String user = "it-u-" + UUID.randomUUID();
        try {
            var occ = store.occupyQuota(user, "sid-a", 5);
            store.promotePending(occ.pendingSandboxId(), "sbx-p3", "2026-12-01T00:00:00Z");
            var before = store.findBySessionId("sid-a").orElseThrow();

            Thread.sleep(2);
            // 归属有效 → 占位（updated_at 刷新，acquire 即占位封 grace 空窗）
            assertTrue(store.occupyWrite("sbx-p3", java.util.List.of("sid-a")));
            var after = store.findBySessionId("sid-a").orElseThrow();
            assertNotEquals(before.updatedAt(), after.updatedAt());

            // 归他会话 / 漂移 → affected=0（close + 降级的判据）
            assertFalse(store.occupyWrite("sbx-p3", java.util.List.of("other-sid")));
            assertFalse(store.occupyWrite("sbx-not-bound", java.util.List.of("sid-a")));
        } finally {
            cleanupUser(ds, user);
        }
    }

    // ---- §4.1.2 定时清理 ----

    @Test
    void cleanupExpiredRecomputesAndPrunesEmptyAnchorsIdempotently() throws Exception {
        var ds = dataSource();
        var store = store(ds);
        String user1 = "it-u-1-" + UUID.randomUUID();   // 过期绑定 + 老锚行 → 行删 + 锚删
        String user2 = "it-u-2-" + UUID.randomUUID();   // 过期绑定 + 新锚行 → 行删 + 锚保留
        try {
            // 用户 1：一行已过期（回拨 8 天 > 7 天保留窗）+ 锚行同批老化
            var occ = store.occupyQuota(user1, "s1", 5);
            store.promotePending(occ.pendingSandboxId(), "sbx-old", "2020-01-01T00:00:00Z");
            exec(ds, "UPDATE sandbox_binding SET expires_at = NOW(3) - INTERVAL 8 DAY WHERE sandbox_id='sbx-old'");
            exec(ds, "UPDATE sandbox_user SET updated_at = NOW(3) - INTERVAL 8 DAY WHERE user_key='" + user1 + "'");
            // 用户 2：过期绑定 + 锚行保持新鲜（仅删行、锚行不删）
            var occ2 = store.occupyQuota(user2, "s2", 5);
            store.promotePending(occ2.pendingSandboxId(), "sbx-gone", "2020-01-01T00:00:00Z");
            exec(ds, "UPDATE sandbox_binding SET expires_at = NOW(3) - INTERVAL 8 DAY WHERE sandbox_id='sbx-gone'");

            var cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(7));
            assertEquals(2, store.cleanupExpired(cutoff));   // 保留窗外行删（分用户锚行锁内重算）
            assertEquals(0, scalar(ds, "SELECT binding_count FROM sandbox_user WHERE user_key='" + user1 + "'"),
                "计数锚行锁内重算");
            // 语义注记：cleanupExpired 的重算 UPDATE 会刷新受影响锚行 updated_at（ON UPDATE）
            // → 当轮锚行均为「新鲜」，空锚行由后续轮次老化后回收；此处回拨验证删除路径
            assertEquals(0, store.cleanupEmptyAnchors(cutoff));
            exec(ds, "UPDATE sandbox_user SET updated_at = NOW(3) - INTERVAL 8 DAY WHERE user_key='" + user1 + "'");
            assertEquals(1, store.cleanupEmptyAnchors(cutoff));   // 仅删「空 + 老化」锚行（用户 1）
            assertEquals(0, scalar(ds, "SELECT COUNT(*) FROM sandbox_user WHERE user_key='" + user1 + "'"));
            assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM sandbox_user WHERE user_key='" + user2 + "'"),
                "锚行仍新鲜（或仍有行）→ 保留");

            // 重跑幂等（第二次删 0 行）
            assertEquals(0, store.cleanupExpired(cutoff));
            assertEquals(0, store.cleanupEmptyAnchors(cutoff));
        } finally {
            cleanupUser(ds, user1);
            cleanupUser(ds, user2);
        }
    }

    @Test
    void v15TablesUseMillisecondPrecision() throws Exception {
        var ds = dataSource();
        store(ds);   // 触发迁移
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DATA_TYPE FROM information_schema.COLUMNS "
                 + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='sandbox_binding' "
                 + "AND COLUMN_NAME='updated_at'")) {
            assertTrue(rs.next());
            assertEquals("datetime", rs.getString(1));
        }
        // 终审 d2 判据依赖毫秒精度（同秒并发 touch 可分辨）
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS "
                 + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='sandbox_binding' "
                 + "AND COLUMN_NAME='updated_at'")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }
    }
}
