package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
            super(facade, pool, deadlineMinutes, sandboxConfig(), leaseStore);
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
            SandboxConfig.PoolConfig.defaults(),
            new SandboxConfig.OpenSandboxConfig("server:8090", "k"));
    }

    private static SandboxConfig.PoolConfig pool(int maxWaitSeconds) {
        // 默认池参数：maxWait 可调（超时用例取 1s 加速）
        return new SandboxConfig.PoolConfig(true, 2, 8, maxWaitSeconds, 600, 60, 2);
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
        var tooSmall = new SandboxConfig.PoolConfig(true, 2, 8, 120, 300, 60, 2); // 300s < 10min
        assertThrows(IllegalStateException.class, () -> new TestablePool(tooSmall, 10, null));
    }

    @Test
    void d15IdleReturnEqualDeadlineShouldStart() {
        assertDoesNotThrow(() -> new TestablePool(pool(120), 10, null));
    }

    @Test
    void d15DeadlineDisabledShouldExemptValidation() {
        // 截止关闭（≤0）时池校验豁免（D16 独立于池化先行落地的兼容面）
        var tooSmall = new SandboxConfig.PoolConfig(true, 2, 8, 120, 60, 60, 2);
        assertDoesNotThrow(() -> new TestablePool(tooSmall, 0, null));
    }

    // ---------------------------------------------------------------- 借出状态机

    @Test
    void borrowFromIdleShouldSetLeaseAndReturnContainer() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "sbx-1");
        var borrowed = pool.borrow("webui-1", "u1");
        assertEquals("sbx-1", borrowed.sandboxId());
        // 续约①：借出即 SET 租约（TTL=idleReturnSeconds）
        verify(redis).setex(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"), eq(600L), eq("webui-1"));
    }

    @Test
    void borrowShouldCarrySidAndUserKeyIntoLedgerArgs() {
        // P1 userKey 贯穿（D17 配额键）：借出 Lua 的 busyJson 携带 sid + userKey
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "sbx-1");
        pool.borrow("webui-9", "alice");
        assertEquals(1, pool.borrowArgs.size());
        var args = pool.borrowArgs.get(0);
        assertEquals(2, args.length);
        assertTrue(args[0].contains("\"sid\":\"webui-9\""), "busyJson 应含 D14 查询键 sid: " + args[0]);
        assertTrue(args[0].contains("\"userKey\":\"alice\""), "busyJson 应含 D17 配额键 userKey: " + args[0]);
        assertEquals("webui-9", args[1], "ARGV[2] 应为 s:{sid} 反查键写入值");
    }

    @Test
    void borrowShouldWaitAndFailWithPoolExhaustedWhenFull() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "", "", "", "", ""); // 直取与轮询持续为空
        reply(SandboxPoolManager.CAP_LUA, "", "", "", "", "");    // 容量已满
        var ex = assertThrows(SandboxException.class, () -> pool.borrow("webui-1", "u1"));
        assertEquals(SandboxErrorCode.CONFIGURATION_ERROR, ex.getErrorCode());
        assertTrue(String.valueOf(ex.getMessage()).contains("sandbox pool exhausted"));
    }

    @Test
    void borrowShouldCreateOnDemandWhenCapacityAvailable() {
        var pool = new TestablePool(pool(1), 10, null);
        reply(SandboxPoolManager.BORROW_LUA, "");
        reply(SandboxPoolManager.CAP_LUA, "p1:1000"); // 容量未满 → 占 creating 名额
        var borrowed = pool.borrow("webui-2", "u1");
        assertEquals("new-1", borrowed.sandboxId());
        // 按需建直接转 busy 给本次借出（消除死等）+ SET 租约
        assertTrue(evalCalls.getOrDefault("promoteBusy", 0) >= 1);
        verify(redis).setex(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "new-1"), eq(600L), eq("webui-2"));
    }

    @Test
    void borrowShouldReplaceContainerWhenProbeFails() {
        var pool = new TestablePool(pool(1), 10, null);
        pool.alive.put("sbx-dead", false); // 借出的容器已死（Server GC 等）
        reply(SandboxPoolManager.BORROW_LUA, "sbx-dead", "sbx-2");
        var borrowed = pool.borrow("webui-3", "u1");
        // 探测失败 kill 换新（§5.2 步骤 2），拿到第二个容器
        assertEquals("sbx-2", borrowed.sandboxId());
        assertTrue(pool.killed.contains("sbx-dead"));
        // 死容器认领移除 busy（HDEL b: + s: 清理）
        assertTrue(evalCalls.getOrDefault("claimRecycle", 0) >= 1);
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
        reply(SandboxPoolManager.BORROW_LUA, "sbx-a", "", "", "", "");
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
    void scanShouldReclaimExpiredBusyAndRebuild() {
        var pool = new TestablePool(pool(1), 10, null);
        seedBusy(pool, "sbx-1", busyJson("webui-1", System.currentTimeMillis() / 1000 - 3600));
        when(redis.exists(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"))).thenReturn(0L);
        pool.startBackgroundExecutors();
        reply(SandboxPoolManager.CLAIM_RECYCLE_LUA, "sbx-1");
        reply(SandboxPoolManager.CAP_LUA, "p1:3000");
        pool.scan();
        assertTrue(pool.killed.contains("sbx-1"));
        verify(redis).del(eq("pfx:" + SandboxPoolManager.LEASE_PREFIX + "sbx-1"));
        pool.awaitBuilt(1);
        assertEquals(1, pool.builtIds.size()); // 异步补位入池
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
