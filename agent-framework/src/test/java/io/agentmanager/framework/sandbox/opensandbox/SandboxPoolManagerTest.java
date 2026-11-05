package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

/**
 * 沙箱池管理器单测（sandbox-pool-design §8 清单的 mock 层覆盖）：
 * 借出/归还状态机、max-wait 超时、探测失败换新、D14 双保险、D15 启动 fail-fast、
 * 宽限规则、认领-续约竞态撤销、Redis fail-open、确认 touch（P3 sid 匹配防线）、
 * resume 账本校正（D2 D17 扩展）、renew KEYS 修复（缺陷①）、D13 delete 路由、
 * 优雅停机不杀容器（D12）。
 *
 * <p>Redis 侧以 {@link RedisClusterCommands} mock 驱动（按 Lua 脚本常量精确分派），
 * OpenSandbox Server 侧以测试子类覆写 {@code connect/buildSandbox} 脱离真实容器；
 * Lua 脚本本身的原子性与 Cluster CROSSSLOT 行为属真实 Redis 集成验收（§10，owner 环境）。
 */
class SandboxPoolManagerTest {

    private RedisClusterCommands<String, String> redis;
    private RedisConnectionFacade facade;
    private TurnLeaseStore turnLeaseStore;

    /** eval 分派的可编程回复（按脚本常量 → 依次返回值） */
    private final Map<String, java.util.ArrayDeque<Object>> evalReplies = new HashMap<>();
    private final Map<String, Integer> evalCalls = new HashMap<>();
    /** 记录每次 eval 的 KEYS[1]（缺陷①断言：renew/touch 的 KEYS 必须是租约键、从不写 ledger 键） */
    private final java.util.List<String> keyCalls = new java.util.ArrayList<>();

    /** 记录 setex(lease) 调用（D14 重置租约 / 续约① 断言） */
    private final java.util.List<String> leaseSetCalls = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        redis = mock(RedisClusterCommands.class);
        facade = mock(RedisConnectionFacade.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        when(facade.key(anyString())).thenAnswer(inv -> "pfx:" + inv.getArgument(0));
        when(facade.sync()).thenReturn(redis);
        // eval 不经 redis mock：TestablePool 覆写 evalLuaKeyed 按 Lua 常量分派（绕开 Mockito varargs 匹配）
        // 租约操作默认成功（setex 记录调用供 D14 重置断言）
        when(redis.exists(anyString())).thenReturn(0L);
        org.mockito.Mockito.doAnswer(inv -> {
            // 显式 cast：String.valueOf 的重载解析会把 <T>getArgument 推断成 valueOf(char[]) → CCE
            leaseSetCalls.add((String) inv.getArgument(0));
            return "OK";
        }).when(redis).setex(anyString(), any(long.class), anyString());
        when(redis.del(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(long.class))).thenReturn(Boolean.TRUE);
        when(redis.hgetall(anyString())).thenReturn(Map.of());
        when(redis.hkeys(anyString())).thenReturn(java.util.List.of());
    }

    private static String luaKey(String script) {
        if (SandboxPoolManager.BORROW_LUA.equals(script)) return "borrow";
        if (SandboxPoolManager.CAP_LUA.equals(script)) return "cap";
        if (SandboxPoolManager.CLAIM_RECYCLE_LUA.equals(script)) return "claimRecycle";
        if (SandboxPoolManager.UNCLAIM_LUA.equals(script)) return "unclaim";
        if (SandboxPoolManager.PROMOTE_IDLE_LUA.equals(script)) return "promoteIdle";
        if (SandboxPoolManager.PROMOTE_BUSY_LUA.equals(script)) return "promoteBusy";
        if (SandboxPoolManager.RELEASE_CREATING_LUA.equals(script)) return "releaseCreating";
        if (SandboxPoolManager.CLAIM_IDLE_LUA.equals(script)) return "claimIdle";
        if (SandboxPoolManager.PRUNE_CREATING_LUA.equals(script)) return "prune";
        if (SandboxPoolManager.RENEW_LEASE_LUA.equals(script)) return "renew";
        if (SandboxPoolManager.TOUCH_LEASE_LUA.equals(script)) return "touchLease";
        if (SandboxPoolManager.TOUCH_PROBE_LUA.equals(script)) return "touchProbe";
        if (SandboxPoolManager.RESUME_RECONCILE_LUA.equals(script)) return "resumeReconcile";
        if (SandboxPoolManager.SCALE_LUA.equals(script)) return "scale";
        if (SandboxPoolManager.RETURN_STICKY_LUA.equals(script)) return "returnSticky";
        if (SandboxPoolManager.BACKFILL_BUSY_LUA.equals(script)) return "backfill";
        return "unknown";
    }

    /** 编程某 Lua 脚本的依次回复（键经 luaKey 归一，与 evalLuaKeyed 覆写一致） */
    private void reply(String lua, Object... values) {
        var q = new java.util.ArrayDeque<Object>();
        for (var v : values) {
            q.add(v);
        }
        evalReplies.put(luaKey(lua), q);
    }

    /** 测试子类：脱离真实 OpenSandbox Server（connect/build 全 mock）+ Redis Lua 全部分派 */
    class TestablePool extends SandboxPoolManager {
        final Map<String, Boolean> alive = new HashMap<>();
        final java.util.List<String> killed = new java.util.ArrayList<>();
        final java.util.List<String> builtIds = new java.util.ArrayList<>();
        /** 捕获 BORROW_LUA 的 ARGV（P1 断言：busyJson 携带 userKey） */
        final java.util.List<String[]> borrowArgs = new java.util.ArrayList<>();

        TestablePool(SandboxConfig.PoolConfig pool, int deadlineMinutes, TurnLeaseStore leaseStore) {
            this(pool, deadlineMinutes, leaseStore, null);
        }

        TestablePool(SandboxConfig.PoolConfig pool, int deadlineMinutes, TurnLeaseStore leaseStore,
                     SandboxPoolManager.RecycleStopper stopper) {
            super(facade, pool, deadlineMinutes, sandboxConfig(), leaseStore, stopper);
        }

        @Override
        protected Sandbox connect(String sandboxId) {
            return fakeSandbox(sandboxId, alive.getOrDefault(sandboxId, true));
        }

        @Override
        protected Sandbox buildSandbox() {
            var id = "new-" + (builtIds.size() + 1);
            builtIds.add(id);
            alive.put(id, true);
            return fakeSandbox(id, true);
        }

        @Override
        protected void killQuietly(String sandboxId) {
            killed.add(sandboxId);
            alive.put(sandboxId, false);
        }

        /** 等待异步补位完成（rebuildExecutor.submit 是异步；单测轮询等待，超时 2s） */
        void awaitBuilt(int expected) {
            long deadline = System.currentTimeMillis() + 2000;
            while (builtIds.size() < expected && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <T> T evalLuaKeyed(String script, ScriptOutputType type, String[] keys, String[] args) {
            String key = luaKey(script);
            keyCalls.add(key + " " + (keys.length > 0 ? keys[0] : "?"));
            evalCalls.merge(key, 1, Integer::sum);
            if (SandboxPoolManager.BORROW_LUA.equals(script)) {
                borrowArgs.add(args);
            }
            var replies = evalReplies.get(key);
            if (replies != null && !replies.isEmpty()) {
                return (T) replies.poll();
            }
            if (SandboxPoolManager.PRUNE_CREATING_LUA.equals(script)) {
                return (T) Long.valueOf(0L);
            }
            return (T) "";
        }
    }

    private Sandbox fakeSandbox(String id, boolean alive) {
        var osb = mock(Sandbox.class);
        var info = mock(SandboxInfo.class);
        when(info.getId()).thenReturn(alive ? id : null);
        when(osb.getInfo()).thenReturn(info);
        return osb;
    }

    private static SandboxConfig sandboxConfig() {
        return new SandboxConfig(true, "img", 60, 1024, 1,
            java.util.List.of("/entry.sh"), java.time.Duration.ofMillis(100),
            true, true, 900, true, true, true, true, 500, 30,
            new SandboxConfig.OpenSandboxConfig("server:8090", "k"));
    }

    private static SandboxConfig.PoolConfig pool(int maxWaitSeconds) {
        // 默认池参数：maxWait 可调（超时用例取 1s 加速）；D17 配额 5 / 粘滞 600s / D18 步长 5
        return new SandboxConfig.PoolConfig(true, 2, 8, maxWaitSeconds, 600, 60, 2, 5, 600, 5);
    }

    private static String busyJson(String sid, long borrowedAtSec) {
        return busyJson(sid, "u1", borrowedAtSec);
    }

    private static String busyJson(String sid, String userKey, long borrowedAtSec) {
        return "{\"sid\":\"" + sid + "\",\"userKey\":\"" + userKey
            + "\",\"podId\":\"p1\",\"borrowedAt\":" + borrowedAtSec + "}";
    }

    // ---------------------------------------------------------------- D15 启动 fail-fast

    @Test
    void d15IdleReturnBelowDeadlineShouldFailStartup() {
        var tooSmall = new SandboxConfig.PoolConfig(true, 2, 8, 120, 300, 60, 2, 5, 600, 5); // 300s < 10min
        assertThrows(IllegalStateException.class, () -> new TestablePool(tooSmall, 10, null));
    }

    @Test
    void d15IdleReturnEqualDeadlineShouldStart() {
        assertDoesNotThrow(() -> new TestablePool(pool(120), 10, null));
    }

    @Test
    void d15DeadlineDisabledShouldExemptValidation() {
        // 截止关闭（≤0）时池校验豁免（D16 独立于池化先行落地的兼容面）
        var tooSmall = new SandboxConfig.PoolConfig(true, 2, 8, 120, 60, 60, 2, 5, 600, 5);
        assertDoesNotThrow(() -> new TestablePool(tooSmall, 0, null));
    }

    // ---------------------------------------------------------------- 借出状态机

    @Test
    void borrowFromIdleShouldSetLeaseAndReturnContainer() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "B:sbx-1"); // 决策树：配额内借通用 idle
        var borrowed = pool.borrow("webui-1", "u1");
        assertEquals("sbx-1", borrowed.sandboxId());
        // 续约①：借出即 SET 租约（TTL=idleReturnSeconds）
        verify(redis).setex(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"), eq(600L), eq("webui-1"));
    }

    @Test
    void borrowShouldCarrySidAndUserKeyIntoLedgerArgs() {
        // P1 userKey 贯穿（D17 配额键）：决策树 ARGV 携带 sid + userKey + userMax
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "B:sbx-1");
        pool.borrow("webui-9", "alice");
        assertEquals(1, pool.borrowArgs.size());
        var args = pool.borrowArgs.get(0);
        assertEquals(4, args.length);
        assertEquals("webui-9", args[0], "ARGV[1] 应为 s:{sid} 反查键写入值");
        assertEquals("alice", args[1], "ARGV[2] 应为 D17 配额键 userKey");
        assertTrue(args[2].contains("\"sid\":\"webui-9\""), "busyJson 应含 D14 查询键 sid: " + args[2]);
        assertTrue(args[2].contains("\"userKey\":\"alice\""), "busyJson 应含 D17 配额键 userKey: " + args[2]);
        assertEquals("5", args[3], "ARGV[4] 应为 userMaxContainers");
    }

    @Test
    void borrowShouldWaitAndFailWithPoolExhaustedWhenFull() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "", "", "", "", ""); // 直取与轮询持续为空（配额内）
        reply(SandboxPoolManager.CAP_LUA, "", "", "", "", "");    // 容量已满
        var ex = assertThrows(SandboxException.class, () -> pool.borrow("webui-1", "u1"));
        assertEquals(SandboxErrorCode.CONFIGURATION_ERROR, ex.getErrorCode());
        assertTrue(String.valueOf(ex.getMessage()).contains("sandbox pool exhausted"));
    }

    @Test
    void borrowShouldReportUserQuotaExhaustedDistinctly() {
        // D17：held ≥ userMax 且无粘滞空闲 → user_quota_exhausted（与池满可区分、立即报错不等待）
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "Q", "Q");
        var ex = assertThrows(SandboxException.class, () -> pool.borrow("webui-1", "alice"));
        assertEquals(SandboxErrorCode.CONFIGURATION_ERROR, ex.getErrorCode());
        String msg = String.valueOf(ex.getMessage());
        assertTrue(msg.contains("user quota exhausted"), "配额错误应可区分: " + msg);
        assertTrue(msg.contains("user=alice") && msg.contains("max=5"));
        // 配额满立即报错：不进入池空等待（wait-start 事件不发）
        assertTrue(pool.recentEvents(10).stream().anyMatch(e -> "quota-exhausted".equals(e.get("event"))));
        assertTrue(pool.recentEvents(10).stream().noneMatch(e -> "wait-start".equals(e.get("event"))));
    }

    @Test
    void borrowShouldInheritStickyContainerWhenQuotaFull() {
        // D17：held ≥ userMax → LRU 继承该用户粘滞空闲（Lua 单 key 原子换绑，返回 'I:{id}'）
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "I:sbx-sticky");
        var borrowed = pool.borrow("webui-new", "alice");
        assertEquals("sbx-sticky", borrowed.sandboxId());
        // 继承即换绑当前 sid：SET 租约（覆盖旧值，续约①）
        verify(redis).setex(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-sticky"), eq(600L), eq("webui-new"));
        assertTrue(pool.recentEvents(10).stream().anyMatch(e -> "inherit".equals(e.get("event"))));
    }

    @Test
    void borrowShouldCreateOnDemandWhenCapacityAvailable() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "");
        reply(SandboxPoolManager.CAP_LUA, "p1:1000"); // 容量未满 → 占 creating 名额
        var borrowed = pool.borrow("webui-2", "u1");
        assertEquals("new-1", borrowed.sandboxId());
        // 按需建直接转 busy 给本次借出（消除死等）+ SET 租约；T 滞后借出直通（不因 T 等待）
        assertTrue(evalCalls.getOrDefault("promoteBusy", 0) >= 1);
        verify(redis).setex(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "new-1"), eq(600L), eq("webui-2"));
    }

    @Test
    void borrowShouldReplaceContainerWhenProbeFails() {
        var pool = new TestablePool(pool(1), 10, null);
        pool.alive.put("sbx-dead", false); // 借出的容器已死（Server GC 等）
        reply(SandboxPoolManager.BORROW_LUA, "B:sbx-dead", "B:sbx-2");
        var borrowed = pool.borrow("webui-3", "u1");
        // 探测失败 kill 换新（§5.2 步骤 2），拿到第二个容器
        assertEquals("sbx-2", borrowed.sandboxId());
        assertTrue(pool.killed.contains("sbx-dead"));
        // 死容器认领移除 busy（HDEL b: + s: 清理）
        assertTrue(evalCalls.getOrDefault("claimRecycle", 0) >= 1);
    }

    @Test
    void borrowShouldReplaceNearExpiryContainer_d19() {
        // D19 借出剩余寿命校验：expiresAt 距 now < 5min → 视同探测失败 kill 换新
        var pool = new TestablePool(pool(1), 10, null) {
            @Override
            protected Sandbox connect(String sandboxId) {
                var osb = mock(Sandbox.class);
                var info = mock(SandboxInfo.class);
                if ("sbx-near".equals(sandboxId)) {
                    when(info.getId()).thenReturn("sbx-near");
                    // 剩余 2min（< 5min 阈值）→ 弃用
                    when(info.getExpiresAt()).thenReturn(java.time.OffsetDateTime.now().plusMinutes(2));
                } else {
                    when(info.getId()).thenReturn(sandboxId);
                    when(info.getExpiresAt()).thenReturn(java.time.OffsetDateTime.now().plusHours(1));
                }
                when(osb.getInfo()).thenReturn(info);
                return osb;
            }
        };
        reply(SandboxPoolManager.BORROW_LUA, "B:sbx-near", "B:sbx-fresh");
        var borrowed = pool.borrow("webui-near", "u1");
        assertEquals("sbx-fresh", borrowed.sandboxId());
        assertTrue(pool.killed.contains("sbx-near"), "临期容器应被 kill 换新");
    }

    @Test
    void borrowShouldFailAfterConsecutiveProbeFailures() {
        // OpenSandbox Server 异常期：借出与按需建产出的容器全部探测失败
        var pool = new TestablePool(pool(1), 10, null) {
            @Override
            protected Sandbox buildSandbox() {
                var id = "dead-new-" + (builtIds.size() + 1);
                builtIds.add(id);
                alive.put(id, false);
                return fakeSandbox(id, false);
            }
        };
        pool.alive.put("sbx-a", false);
        // 借出 + 按需建 + 换新各探测失败（PROBE_MAX_RETRY+1）→ 走池空等待 → 超时报错
        reply(SandboxPoolManager.BORROW_LUA, "B:sbx-a", "", "", "", "");
        reply(SandboxPoolManager.CAP_LUA, "p1:1001", "p1:1002", "p1:1003", "p1:1004", "p1:1005");
        var ex = assertThrows(SandboxException.class, () -> pool.borrow("webui-4", "u1"));
        assertTrue(String.valueOf(ex.getMessage()).contains("pool exhausted"));
        // 至少两次探测失败被 kill（死容器不留在 busy 账本）
        assertTrue(pool.killed.size() >= 2);
    }

    @Test
    void failOpenBorrowShouldCreateUnmanagedContainerWhenRedisDown() {
        var pool = new TestablePool(pool(1), 10, null);
        var borrowed = pool.borrowFailOpen("webui-5", "u1");
        assertTrue(borrowed.sandboxId().startsWith("new-"));
        // 账本外容器：不写 ledger、不 SET 租约（Server TTL 兜底消亡）
        verify(redis, never()).setex(anyString(), any(long.class), anyString());
    }

    // ---------------------------------------------------------------- 归还（D13 delete 路由）

    @Test
    void releaseShouldKillClaimAndRebuild() {
        var pool = new TestablePool(pool(1), 10, null);
        pool.startBackgroundExecutors();
        reply(SandboxPoolManager.CLAIM_RECYCLE_LUA, "sbx-1");
        reply(SandboxPoolManager.CAP_LUA, "p1:2000");
        pool.release("sbx-1");
        assertTrue(pool.killed.contains("sbx-1"));
        verify(redis).del(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"));
        pool.awaitBuilt(1); // 异步补位：kill + 认领后应补一个新容器入池
        assertTrue(evalCalls.getOrDefault("cap", 0) >= 1); // 补位占容量
        assertEquals(1, pool.builtIds.size());
    }

    @Test
    void releaseShouldSkipRebuildWhenClaimLost() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.CLAIM_RECYCLE_LUA, ""); // 认领失败（fail-open 容器/已被他人处理）
        pool.release("sbx-x");
        assertTrue(pool.killed.contains("sbx-x"));
        assertEquals(0, evalCalls.getOrDefault("cap", 0)); // 认领失败不补位
    }

    // ---------------------------------------------------------------- 扫描：D14 双保险 / 宽限 / 认领竞态 / 保活

    private void seedBusy(TestablePool pool, String id, String json) {
        var ledger = new HashMap<String, String>();
        ledger.put("b:" + id, json);
        when(redis.hgetall(eq("pfx:" + SandboxPoolManager.LEDGER_KEY))).thenReturn(ledger);
    }

    @Test
    void scanShouldSkipRecycleWhenTurnLeaseActive_d14() {
        var pool = new TestablePool(pool(1), 10, turnLeaseStore);
        seedBusy(pool, "sbx-1", busyJson("webui-1", System.currentTimeMillis() / 1000 - 3600));
        when(redis.exists(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"))).thenReturn(0L);
        when(turnLeaseStore.isHeldStrict("webui-1")).thenReturn(true); // turn 进行中（LLM 长生成）
        pool.scan();
        // D14：重置租约、绝不 kill
        assertTrue(leaseSetOrRenewed());
        assertTrue(pool.killed.isEmpty());
    }

    @Test
    void scanShouldSkipRecycleWhenTurnLeaseProbeUnavailable_d14() {
        var pool = new TestablePool(pool(1), 10, turnLeaseStore);
        seedBusy(pool, "sbx-1", busyJson("webui-1", System.currentTimeMillis() / 1000 - 3600));
        when(redis.exists(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"))).thenReturn(0L);
        when(turnLeaseStore.isHeldStrict("webui-1")).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(() -> pool.scan()); // 不可判定 → 跳过本轮（宁延迟不误杀）
        assertTrue(pool.killed.isEmpty());
    }

    @Test
    void scanShouldRespectBorrowGraceWindow() {
        var pool = new TestablePool(pool(1), 10, null);
        seedBusy(pool, "sbx-1", busyJson("webui-1", System.currentTimeMillis() / 1000)); // 刚借出
        when(redis.exists(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"))).thenReturn(0L);
        pool.scan();
        assertTrue(pool.killed.isEmpty()); // borrowedAt 宽限（60s）内跳过
    }

    @Test
    void scanShouldReturnExpiredBusyToStickyIdle_d17() {
        // D17 归还转粘滞：复核通过 → DEL 租约 → HSET i:（owner=userKey，不 kill 不补位）
        var pool = new TestablePool(pool(1), 10, null);
        seedBusy(pool, "sbx-1", busyJson("webui-1", "alice", System.currentTimeMillis() / 1000 - 3600));
        when(redis.exists(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"))).thenReturn(0L);
        pool.startBackgroundExecutors();
        reply(SandboxPoolManager.CLAIM_RECYCLE_LUA, "sbx-1");
        reply(SandboxPoolManager.RETURN_STICKY_LUA, 1L);
        pool.scan();
        // 转粘滞：不 kill、不补位，回写钩子缺省（stopper=null）直接转粘滞（容器数据保留到粘滞窗口）
        assertFalse(pool.killed.contains("sbx-1"), "归还容器不 kill（D17 粘滞窗口）");
        assertEquals(1, evalCalls.getOrDefault("returnSticky", 0));
        assertEquals(0, evalCalls.getOrDefault("cap", 0), "转粘滞不触发通用池补位");
        verify(redis).del(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"));
        assertTrue(pool.recentEvents(10).stream().anyMatch(e -> "return-sticky".equals(e.get("event"))));
    }

    @Test
    void scanShouldKillAndRebuildWhenOwnerUnknown() {
        // D17 跨用户暴露防线：owner 未知（busy.userKey 空串）→ 不转粘滞，维持 kill+rebuild 旧行为
        // （防携用户 /workspace 数据的容器落通用池被他人借走）
        var pool = new TestablePool(pool(1), 10, null);
        seedBusy(pool, "sbx-1", busyJson("webui-1", "", System.currentTimeMillis() / 1000 - 3600));
        when(redis.exists(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"))).thenReturn(0L);
        pool.startBackgroundExecutors();
        reply(SandboxPoolManager.CLAIM_RECYCLE_LUA, "sbx-1");
        reply(SandboxPoolManager.CAP_LUA, "p1:3000");
        pool.scan();
        assertTrue(pool.killed.contains("sbx-1"), "owner 未知的容器必须 kill（跨用户暴露防线）");
        assertEquals(0, evalCalls.getOrDefault("returnSticky", 0));
        pool.awaitBuilt(1);
        assertEquals(1, pool.builtIds.size()); // 异步补位入池
    }

    @Test
    void scanShouldStopBeforeStickyTransferWhenStopperPresent() {
        // D17 §5.3-4b 顺序严格：stop() 兜底（KV 回写）→ DEL 租约 → 转粘滞
        // （stop 内 renewPoolLease 会重建刚 DEL 的租约，故 DEL 必须在 stop 之后）
        var order = new java.util.ArrayList<String>();
        org.mockito.Mockito.doAnswer(inv -> {
            order.add("del");
            return 1L;
        }).when(redis).del(anyString());
        var pool = new TestablePool(pool(1), 10, null,
            (sandboxId, userKey) -> order.add("stop:" + userKey));
        seedBusy(pool, "sbx-1", busyJson("webui-1", "alice", System.currentTimeMillis() / 1000 - 3600));
        when(redis.exists(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"))).thenReturn(0L);
        pool.startBackgroundExecutors();
        reply(SandboxPoolManager.CLAIM_RECYCLE_LUA, "sbx-1");
        reply(SandboxPoolManager.RETURN_STICKY_LUA, 1L);
        pool.scan();
        assertFalse(pool.killed.contains("sbx-1"));
        assertEquals(1, evalCalls.getOrDefault("returnSticky", 0));
        assertTrue(order.contains("stop:alice") && order.contains("del")
                && order.indexOf("stop:alice") < order.indexOf("del"),
            "stop 必须先于 DEL 租约，实际顺序: " + order);
    }

    @Test
    void scanShouldContinueStickyTransferWhenStopperThrows() {
        // fail-soft：stop() 抛异常 → 告警后照常 DEL 租约 + 转粘滞（KV 回写失败不阻塞回收）
        var pool = new TestablePool(pool(1), 10, null,
            (sandboxId, userKey) -> {
                throw new IllegalStateException("kv down");
            });
        seedBusy(pool, "sbx-1", busyJson("webui-1", "alice", System.currentTimeMillis() / 1000 - 3600));
        when(redis.exists(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"))).thenReturn(0L);
        pool.startBackgroundExecutors();
        reply(SandboxPoolManager.CLAIM_RECYCLE_LUA, "sbx-1");
        reply(SandboxPoolManager.RETURN_STICKY_LUA, 1L);
        assertDoesNotThrow(() -> pool.scan());
        assertEquals(1, evalCalls.getOrDefault("returnSticky", 0));
        assertFalse(pool.killed.contains("sbx-1"));
    }

    @Test
    void scanShouldRevertClaimWhenLeaseRenewedBeforeKill() {
        var pool = new TestablePool(pool(1), 10, null);
        seedBusy(pool, "sbx-1", busyJson("webui-1", System.currentTimeMillis() / 1000 - 3600));
        // 认领成功 → kill 前复核：租约被 resume 续约重建
        when(redis.exists(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1")))
            .thenReturn(0L, 1L);
        reply(SandboxPoolManager.CLAIM_RECYCLE_LUA, "sbx-1");
        pool.scan();
        // 撤销认领（busy 与 s: 反查一并还原，容器存活续跑——设计 §5.3 4a）
        assertTrue(evalCalls.getOrDefault("unclaim", 0) >= 1);
        assertTrue(pool.killed.isEmpty());
    }

    @Test
    void scanShouldReapDeadIdleContainer() {
        var pool = new TestablePool(pool(1), 10, null);
        var ledger = new HashMap<String, String>();
        ledger.put("i:sbx-9", "1000");
        when(redis.hgetall(eq("pfx:" + SandboxPoolManager.LEDGER_KEY))).thenReturn(ledger);
        pool.alive.put("sbx-9", false); // Server 空闲 GC 兜底失效
        pool.startBackgroundExecutors();
        reply(SandboxPoolManager.CLAIM_IDLE_LUA, "sbx-9");
        reply(SandboxPoolManager.CAP_LUA, "p1:4000");
        pool.scan();
        assertTrue(pool.killed.contains("sbx-9"));
        pool.awaitBuilt(1);
        assertEquals(1, pool.builtIds.size());
    }

    @Test
    void scanShouldPruneStaleCreatingEntries() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.PRUNE_CREATING_LUA, 2L);
        pool.scan();
        // 剪枝脚本执行（删除数由 Redis 端裁决，mock 返回 2）
        assertEquals(1, evalCalls.getOrDefault("prune", 0));
    }

    // ---------------------------------------------------------------- D17 粘滞空闲生命周期

    private void seedIdle(TestablePool pool, String id, String json) {
        var ledger = new HashMap<String, String>();
        ledger.put("i:" + id, json);
        when(redis.hgetall(eq("pfx:" + SandboxPoolManager.LEDGER_KEY))).thenReturn(ledger);
    }

    @Test
    void scanShouldKillExpiredStickyIdleAndFreeQuota_d17() {
        // 粘滞 TTL 到期 → 认领 + kill（释放配额与容量）+ 通用水面补位核算
        var pool = new TestablePool(pool(1), 10, null);
        long old = System.currentTimeMillis() / 1000 - 700; // > STICKY_TTL(600s)
        seedIdle(pool, "sbx-s", "{\"owner\":\"alice\",\"returnedAt\":" + old + ",\"sid\":\"webui-old\"}");
        pool.startBackgroundExecutors();
        reply(SandboxPoolManager.CLAIM_IDLE_LUA, "sbx-s");
        reply(SandboxPoolManager.CAP_LUA, "p1:5000");
        reply(SandboxPoolManager.SCALE_LUA, "K:5"); // 水位无变更
        pool.scan();
        assertTrue(pool.killed.contains("sbx-s"), "粘滞到期容器应 kill 回收");
        assertTrue(pool.recentEvents(10).stream().anyMatch(e -> "sticky-expire".equals(e.get("event"))));
        pool.awaitBuilt(1);
        assertEquals(1, pool.builtIds.size()); // 通用池按水面补位
    }

    @Test
    void scanShouldKeepFreshStickyIdleAlive_d17() {
        // 粘滞窗口内（未到期）：不 kill，仅保活检查
        var pool = new TestablePool(pool(1), 10, null);
        long fresh = System.currentTimeMillis() / 1000 - 30;
        seedIdle(pool, "sbx-s", "{\"owner\":\"alice\",\"returnedAt\":" + fresh + ",\"sid\":\"webui-old\"}");
        reply(SandboxPoolManager.SCALE_LUA, "K:5");
        pool.scan();
        assertTrue(pool.killed.isEmpty(), "粘滞窗口内容器保留（继承/复活窗口）");
        assertEquals(0, evalCalls.getOrDefault("claimIdle", 0));
    }

    @Test
    void scanShouldTreatLegacyIdleValueAsGeneric() {
        // 账本 v2 旧值容忍：v1 i: 值为数字串 → 通用空闲（粘滞语义不误触，rollout 期禁破坏性清理）
        var pool = new TestablePool(pool(1), 10, null);
        seedIdle(pool, "sbx-legacy", "0");
        reply(SandboxPoolManager.SCALE_LUA, "K:5");
        pool.scan();
        assertTrue(pool.killed.isEmpty());
        assertEquals(0, evalCalls.getOrDefault("claimIdle", 0), "数字串旧值视作通用空闲，不按粘滞到期处理");
    }

    @Test
    void backfillShouldBeNoOpWithoutLedgerEntry() {
        // D17 回填原子化：b:{id} 不存在 → Lua no-op（不重建幻影 busy）；mock 层验证调用收口与 fail-soft
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BACKFILL_BUSY_LUA, 0L);
        pool.backfillBusyIdentity("sbx-1", "sid-1", "alice");
        assertEquals(1, evalCalls.getOrDefault("backfill", 0));
        assertTrue(keyCalls.stream().anyMatch(k -> k.startsWith("backfill pfx:" + SandboxPoolManager.LEDGER_KEY)),
            "回填是 ledger 单 key Lua");
    }

    @Test
    void backfillShouldSwallowRedisFailure() {
        var pool = new TestablePool(pool(1), 10, null) {
            @Override
            protected <T> T evalLuaKeyed(String script, ScriptOutputType type, String[] keys, String[] args) {
                throw new RedisException("down");
            }
        };
        assertDoesNotThrow(() -> pool.backfillBusyIdentity("sbx-1", "sid-1", "alice"));
    }

    // ---------------------------------------------------------------- D18 弹性水位

    private void replyScaleUpDeficit(int deficit) {
        reply(SandboxPoolManager.SCALE_LUA, "U:10:" + deficit);
    }

    @Test
    void scaleOutShouldRebuildGenericIdleToTarget_d18() {
        // 80% 抬升 → 异步补建通用 idle 至 T（缺口数由 Lua 返回，rebuildOnce 内 Lua cap 兜底不超额）
        var pool = new TestablePool(pool(1), 10, null);
        replyScaleUpDeficit(3);
        reply(SandboxPoolManager.CAP_LUA, "p1:6001", "p1:6002", "p1:6003");
        pool.startBackgroundExecutors();
        pool.scan();
        pool.awaitBuilt(3);
        assertEquals(3, pool.builtIds.size(), "按 Lua 缺口数补建通用 idle");
        assertTrue(pool.recentEvents(10).stream().anyMatch(e -> "scale-out".equals(e.get("event"))));
    }

    @Test
    void scaleInShouldLruKillOnlyGenericIdle_d18() {
        // <50%×3 周期+冷却回缩 → 对 Lua 返回的 LRU id 逐个认领 kill（粘滞不参与——id 列表只含通用）
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.SCALE_LUA, "D:5|sbx-g1,sbx-g2");
        reply(SandboxPoolManager.CLAIM_IDLE_LUA, "sbx-g1", "sbx-g2"); // 认领先到先得
        pool.startBackgroundExecutors();
        pool.scan();
        assertTrue(pool.killed.contains("sbx-g1") && pool.killed.contains("sbx-g2"));
        assertTrue(pool.recentEvents(10).stream().anyMatch(e -> "scale-in".equals(e.get("event"))));
    }

    @Test
    void scaleKeepShouldNotTriggerRebuildOrKill_d18() {
        // 'K'（含冷却窗内被拒）：无补建无 kill（§8「冷却窗二次变更被拒」mock 层语义）
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.SCALE_LUA, "K:5");
        pool.startBackgroundExecutors();
        pool.scan();
        pool.awaitBuilt(0);
        assertEquals(0, pool.builtIds.size());
        assertTrue(pool.killed.isEmpty());
    }

    // ---------------------------------------------------------------- D19 资源双档

    @Test
    void buildSandboxShouldCarryResourceRequests_d19() {
        // D19：池路径 buildSandbox 透传 resourceRequests（CPU request=100m / memory=128Mi 默认）
        var osb = mock(Sandbox.class);
        var info = mock(SandboxInfo.class);
        when(info.getId()).thenReturn("sb-req");
        when(osb.getInfo()).thenReturn(info);
        try (MockedStatic<Sandbox> mocked = mockStatic(Sandbox.class)) {
            var builder = mock(Sandbox.Builder.class);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.resource(anyMap())).thenReturn(builder);
            when(builder.resourceRequests(anyMap())).thenReturn(builder);
            when(builder.env(anyMap())).thenReturn(builder);
            when(builder.build()).thenReturn(osb);
            mocked.when(Sandbox::builder).thenReturn(builder);

            // 直接实例（不经 TestablePool 的 buildSandbox 覆写）
            var manager = new SandboxPoolManager(facade, pool(1), 10, sandboxConfig(), null);
            manager.buildSandbox();
            verify(builder).resourceRequests(eq(Map.of("cpu", "100m", "memory", "128Mi")));
        }
    }

    // ---------------------------------------------------------------- 确认入口 touch（D16-④ + P3 sid 防线）

    @Test
    void touchShouldRenewLeaseWhenAlive() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.TOUCH_PROBE_LUA, "B:sbx-1");
        reply(SandboxPoolManager.TOUCH_LEASE_LUA, 1L);
        pool.touchSessionOrThrow("webui-1"); // 放行
        // 缺陷③：EXISTS→EXPIRE 同脚本原子执行于租约键
        assertTrue(keyCalls.contains("touchLease pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"));
        assertFalse(keyCalls.contains("touchLease pfx:" + SandboxPoolManager.LEDGER_KEY),
            "touch 判活绝不写 ledger 键（WRONGTYPE 污染面）");
    }

    @Test
    void touchShouldThrowWhenLeaseGone() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.TOUCH_PROBE_LUA, "B:sbx-1");
        reply(SandboxPoolManager.TOUCH_LEASE_LUA, 0L); // 租约确死（原子脚本返回 0）
        assertThrows(SandboxPoolManager.PoolLeaseGoneException.class, () -> pool.touchSessionOrThrow("webui-1"));
    }

    @Test
    void touchShouldAllowWhenSessionNeverBorrowed() {
        // 缺陷②反转（原 touchShouldThrowWhenNoBusyEntryForSession）：s: 无映射
        // （会话从未经池借出 / fail-open 容器 / 池刚开启）→ 放行，确认 200 流程不被误 410
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.TOUCH_PROBE_LUA, "");
        assertDoesNotThrow(() -> pool.touchSessionOrThrow("webui-1"));
        verify(redis, never()).setex(anyString(), any(long.class), anyString());
    }

    @Test
    void touchShouldThrowWhenStaleMappingSidMismatched() {
        // P3 防线：陈旧 s: 指向已被他人借走/继承的容器（条目 sid 不匹配）→ 干净 410，绝不放行
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.TOUCH_PROBE_LUA, "X");
        var ex = assertThrows(SandboxPoolManager.PoolLeaseGoneException.class,
            () -> pool.touchSessionOrThrow("webui-1"));
        assertTrue(ex.getMessage().contains("verdict=X"));
    }

    @Test
    void touchShouldThrowWhenContainerAlreadyReclaimed() {
        // s: 命中但 b:/i: 均无条目（容器在回收通道）→ 410（防"通过预检却中途 404"）
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.TOUCH_PROBE_LUA, "N");
        assertThrows(SandboxPoolManager.PoolLeaseGoneException.class, () -> pool.touchSessionOrThrow("webui-1"));
    }

    @Test
    void touchShouldReviveStickyContainerAndSetLease() {
        // D17 粘滞窗口内确认：探查 Lua 已原子复活回 busy（'R'）→ SET 租约放行（与继承竞态单赢家裁决）
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.TOUCH_PROBE_LUA, "R:sbx-1");
        pool.touchSessionOrThrow("webui-1");
        verify(redis).setex(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"), eq(600L), eq("webui-1"));
    }

    @Test
    void touchShouldFailOpenWhenRedisUnavailable() {
        var pool = new TestablePool(pool(1), 10, null) {
            @Override
            protected <T> T evalLuaKeyed(String script, ScriptOutputType type, String[] keys, String[] args) {
                throw new RedisException("down");
            }
        };
        assertDoesNotThrow(() -> pool.touchSessionOrThrow("webui-1")); // Redis 故障不阻断确认
    }

    // ---------------------------------------------------------------- resume 账本校正（D2 D17 扩展 + P4 顺序修正）

    @Test
    void reconcileResumeShouldRenewLeaseForOwnBusyEntry() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.RESUME_RECONCILE_LUA, "B");
        assertTrue(pool.reconcileResume("sbx-1", "webui-1", "u1"));
        verify(redis).setex(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"), eq(600L), eq("webui-1"));
    }

    @Test
    void reconcileResumeShouldReviveStickyEntry() {
        // 粘滞复活：'R' = Lua 已原子换绑（HDEL i: + HSET b:）→ Java SET 租约（复活即续约①）
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.RESUME_RECONCILE_LUA, "R");
        assertTrue(pool.reconcileResume("sbx-1", "webui-1", "u1"));
        verify(redis).setex(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"), eq(600L), eq("webui-1"));
        // 复活事件进环形缓冲（可观测性）
        assertTrue(pool.recentEvents(10).stream().anyMatch(e -> "resume-revive".equals(e.get("event"))));
    }

    @Test
    void reconcileResumeShouldDowngradeWhenReassignedOrReclaimed() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.RESUME_RECONCILE_LUA, "X"); // 被继承给他人/校验不过
        assertFalse(pool.reconcileResume("sbx-1", "webui-1", "u1"));
        reply(SandboxPoolManager.RESUME_RECONCILE_LUA, "G"); // 已被回收
        assertFalse(pool.reconcileResume("sbx-1", "webui-1", "u1"));
        // P4 顺序修正：降级路径不写租约（无幻影 busy 保活面）
        assertTrue(leaseSetCalls.isEmpty());
    }

    @Test
    void reconcileResumeShouldFailOpenWhenRedisUnavailable() {
        var pool = new TestablePool(pool(1), 10, null) {
            @Override
            protected <T> T evalLuaKeyed(String script, ScriptOutputType type, String[] keys, String[] args) {
                throw new RedisException("down");
            }
        };
        // fail-open：校正失败退化为现状纯续约语义（D14 兜底），不阻断 resume
        assertTrue(pool.reconcileResume("sbx-1", "webui-1", "u1"));
    }

    // ---------------------------------------------------------------- 续约与优雅停机（D12）

    @Test
    void renewShouldTargetLeaseKeyNotLedgerKey() {
        var pool = new TestablePool(pool(1), 10, null);
        pool.renew("sbx-1");
        assertEquals(1, evalCalls.getOrDefault("renew", 0));
        // 缺陷①：RENEW_LEASE_LUA 的 KEYS[1] 必须是租约键——此前误传 ledger 键，
        // 对 HASH 键 GET 触发 WRONGTYPE 静默失效（续约永不生效）且 SET 污染账本
        assertTrue(keyCalls.contains("renew pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"),
            "renew 的 KEYS[1] 应为租约键，实际: " + keyCalls);
        assertFalse(keyCalls.contains("renew pfx:" + SandboxPoolManager.LEDGER_KEY),
            "renew 绝不允许以 ledger 键为 KEYS[1]（WRONGTYPE/污染根因）");
        pool.renew(null); // 空值安全 no-op
        assertEquals(1, evalCalls.getOrDefault("renew", 0));
    }

    @Test
    void renewShouldSwallowRedisFailure() {
        var pool = new TestablePool(pool(1), 10, null) {
            @Override
            protected <T> T evalLuaKeyed(String script, ScriptOutputType type, String[] keys, String[] args) {
                throw new RedisException("down");
            }
        };
        assertDoesNotThrow(() -> pool.renew("sbx-1")); // 续约 fail-soft（D14 兜底）
    }

    @Test
    void shutdownShouldNeverKillContainers_d12() {
        var pool = new TestablePool(pool(1), 10, null);
        pool.shutdown();
        assertTrue(pool.killed.isEmpty()); // 容器是集群资产，优雅停机零销毁
    }

    @Test
    void snapshotShouldExposeCountsAndEvents() {
        var pool = new TestablePool(pool(1), 10, null);
        var ledger = new HashMap<String, String>();
        ledger.put("i:sbx-1", "0");
        ledger.put("b:sbx-2", busyJson("webui-2", 0));
        when(redis.hgetall(eq("pfx:" + SandboxPoolManager.LEDGER_KEY))).thenReturn(ledger);
        var snap = pool.snapshot();
        assertEquals(java.util.List.of("sbx-1"), snap.get("idle"));
        assertEquals(1, ((java.util.List<?>) snap.get("busy")).size());
        assertEquals(8, snap.get("maxSize"));
        pool.borrowFailOpen("webui-e", "u1"); // 产生一条流转事件
        assertFalse(pool.recentEvents(10).isEmpty());
    }

    /** 断言租约被重置（setex 或 renew eval 任一发生） */
    private boolean leaseSetOrRenewed() {
        return !leaseSetCalls.isEmpty() || evalCalls.getOrDefault("renew", 0) > 0;
    }
}
