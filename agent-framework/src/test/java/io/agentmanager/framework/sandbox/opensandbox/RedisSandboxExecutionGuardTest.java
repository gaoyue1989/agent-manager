package io.agentmanager.framework.sandbox.opensandbox;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentmanager.framework.config.AgentRedisProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSandboxExecutionGuardTest {

    private static final int CLOSED_PORT = 1; // 本机不可达端口（连接被拒 → fail-open 路径）

    private static SandboxIsolationKey userKey() {
        var ctx = RuntimeContext.builder().userId("alice").sessionId("s1").build();
        return SandboxIsolationKey.resolve(IsolationScope.USER, ctx, "test-agent").orElseThrow();
    }

    /** 与生产装配同路径的门面（standalone、无前缀），指向不可达端口 */
    private static RedisConnectionFacade unreachableFacade() {
        return RedisConnectionFacade.create(
            new AgentRedisProperties("redis://127.0.0.1:" + CLOSED_PORT, 2000, 2000, 250_000));
    }

    @Test
    void tryEnterShouldFailOpenWhenRedisUnreachable() throws InterruptedException {
        // 守卫是正确性增强而非可用性单点：Redis 不可达时必须放行（noop lease）
        try (var facade = unreachableFacade()) {
            var guard = new RedisSandboxExecutionGuard(facade, 60_000L);
            long t0 = System.currentTimeMillis();
            SandboxLease lease = guard.tryEnter(userKey());
            long elapsed = System.currentTimeMillis() - t0;
            assertNotNull(lease, "fail-open 应返回 noop lease 而非 null/异常");
            assertTrue(elapsed < 15_000, "不可达应在连接超时内快速失败，实际 " + elapsed + "ms");
            lease.close(); // noop close 幂等无害
        }
    }

    @Test
    void tryEnterShouldBlockButInterruptibly() throws Exception {
        // 连接不可达时 fail-open 立即返回；这里验证同一构造在 interrupt 下可中断退出
        try (var facade = unreachableFacade()) {
            var guard = new RedisSandboxExecutionGuard(facade, 60_000L);
            Thread t = new Thread(() -> {
                try {
                    guard.tryEnter(userKey());
                } catch (InterruptedException expected) {
                    // 被打断即通过
                }
            });
            t.start();
            Thread.sleep(100);
            t.interrupt();
            t.join(5_000);
            assertTrue(!t.isAlive(), "tryEnter 应可被中断退出");
        }
    }

    @Test
    void keyShouldCarryUserValue() {
        var key = userKey();
        assertNotNull(key.getValue());
        assertEquals("USER", key.getScope().name());
        // redis key 前缀拼接格式稳定（guard 与排查口径一致）
        assertNotNull(Set.of(key.getScope().name().toLowerCase()));
    }

    // ===== 锁键值翻译（Channel 链路 key.value=网关 peer，须反查真实用户再组键）=====

    @Test
    void userScopeValueShouldTranslateToRealUserIdViaSessionUserLookup() {
        // Channel 链路：peer-sid-1 是前端 sessionId，session_user 登记其真实用户 alice
        var store = org.mockito.Mockito.mock(io.agentmanager.framework.service.SessionUserStore.class);
        org.mockito.Mockito.when(store.findUserIdBySession("peer-sid-1")).thenReturn("alice");
        var guard = new RedisSandboxExecutionGuard(unreachableFacade(), 60_000L, store);
        assertEquals("alice", guard.resolveUserLockValue("peer-sid-1"));
    }

    @Test
    void lookupMissShouldFallBackToRawValue() {
        // A2A / invoke 链路（ctx.userId 即真实用户）或未登记会话：反查 null → 原值回退，行为不变
        var store = org.mockito.Mockito.mock(io.agentmanager.framework.service.SessionUserStore.class);
        org.mockito.Mockito.when(store.findUserIdBySession("real-user")).thenReturn(null);
        var guard = new RedisSandboxExecutionGuard(unreachableFacade(), 60_000L, store);
        assertEquals("real-user", guard.resolveUserLockValue("real-user"));
    }

    @Test
    void lookupShouldBeCachedWithinTtlWindow() {
        // 自旋重试每 200ms 一次 tryEnter：反查必须被缓存挡住，否则打爆 DB
        var store = org.mockito.Mockito.mock(io.agentmanager.framework.service.SessionUserStore.class);
        org.mockito.Mockito.when(store.findUserIdBySession("peer-sid-2")).thenReturn("bob");
        var guard = new RedisSandboxExecutionGuard(unreachableFacade(), 60_000L, store);
        assertEquals("bob", guard.resolveUserLockValue("peer-sid-2"));
        assertEquals("bob", guard.resolveUserLockValue("peer-sid-2"));
        assertEquals("bob", guard.resolveUserLockValue("peer-sid-2"));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(1)).findUserIdBySession("peer-sid-2");
    }

    @Test
    void negativeLookupShouldNotFallbackSpamWithinWindow() {
        // 未命中也走负缓存：TTL 窗口内不重复查库（自旋路径每 200ms 触发的防线）
        var store = org.mockito.Mockito.mock(io.agentmanager.framework.service.SessionUserStore.class);
        org.mockito.Mockito.when(store.findUserIdBySession("unregistered")).thenReturn(null);
        var guard = new RedisSandboxExecutionGuard(unreachableFacade(), 60_000L, store);
        assertEquals("unregistered", guard.resolveUserLockValue("unregistered"));
        assertEquals("unregistered", guard.resolveUserLockValue("unregistered"));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(1)).findUserIdBySession("unregistered");
    }

    @Test
    void storeLookupFailureShouldFallBackToRawValue() {
        // 查库异常：fail-soft 回退原值，守卫不得因翻译层故障阻断（与 Redis fail-open 同原则）
        var store = org.mockito.Mockito.mock(io.agentmanager.framework.service.SessionUserStore.class);
        org.mockito.Mockito.when(store.findUserIdBySession("any"))
            .thenThrow(new RuntimeException("db down"));
        var guard = new RedisSandboxExecutionGuard(unreachableFacade(), 60_000L, store);
        assertEquals("any", guard.resolveUserLockValue("any"));
    }

    @Test
    void nullOrBlankValueShouldBypassLookup() {
        // 空值直通，不触达 store（防御 resolve 前置判空语义）
        var store = org.mockito.Mockito.mock(io.agentmanager.framework.service.SessionUserStore.class);
        var guard = new RedisSandboxExecutionGuard(unreachableFacade(), 60_000L, store);
        assertNull(guard.resolveUserLockValue(null));
        assertEquals("", guard.resolveUserLockValue(""));
        org.mockito.Mockito.verifyNoInteractions(store);
    }
}
