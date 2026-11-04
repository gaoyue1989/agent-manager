package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

/**
 * {@link RedisSandboxEnvLock} 单元测试（mock 门面，模式对齐 A2aJobRedisStoreClaimRaceTest）：
 * SET NX 获租约、键经 {@code facade.key()} 前缀隔离（多服务共享 Redis 防跨服务互锁）、
 * 释放按 token 比较删除、有限等待超时 503 {@code env_update_busy}、Redis 异常 fail-open、
 * token 续租 Lua 三分支（评审 g1：持有期间续租保证互斥，TTL 退化为纯崩溃自愈窗口）、
 * noop 兜底实例与空 sandboxId 防御。
 */
class RedisSandboxEnvLockTest {

    private RedisConnectionFacade facade;
    @SuppressWarnings("unchecked")
    private final RedisClusterCommands<String, String> sync = mock(RedisClusterCommands.class);

    /** 等待窗口 300ms / 自旋 50ms：busy 用例亚秒收敛（生产路径走常量 10s/200ms） */
    private RedisSandboxEnvLock lock;

    @BeforeEach
    void setUp() {
        facade = mock(RedisConnectionFacade.class);
        when(facade.key(anyString())).thenAnswer(inv -> "pfx:" + inv.getArgument(0));
        when(facade.sync()).thenReturn(sync);
        lock = new RedisSandboxEnvLock(facade, 300, 50);
    }

    @Test
    void acquireShouldSetNxOnPrefixedKeyAndReleaseByToken() {
        when(sync.set(anyString(), anyString(), any())).thenReturn("OK");

        RedisSandboxEnvLock.Lease lease = lock.acquire("sbx-1");
        assertNotNull(lease, "NX 成功应返回持有句柄");
        assertNotSame(RedisSandboxEnvLock.Lease.NOOP, lease);

        // 键必须经 facade.key() 前缀隔离：多服务共享 oaf-redis 时裸键 sbx:env:* 会跨服务互锁
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(sync).set(key.capture(), token.capture(), any());
        assertEquals("pfx:sbx:env:sbx-1", key.getValue());

        lease.close();
        // 释放 = Lua 比较 token 删除（防误删他人租约，与守卫同款）
        verify(sync).eval(eq(RedisSandboxEnvLock.RELEASE_LUA), eq(ScriptOutputType.INTEGER),
            aryEq(new String[]{"pfx:sbx:env:sbx-1"}), eq(token.getValue()));
    }

    @Test
    void acquireShouldRetryUntilLeaseGranted() {
        // 第一轮被占用（null）→ 自旋后第二轮 NX 成功
        when(sync.set(anyString(), anyString(), any())).thenReturn(null).thenReturn("OK");

        assertNotNull(lock.acquire("sbx-1"));
        verify(sync, times(2)).set(anyString(), anyString(), any());
    }

    @Test
    void acquireShouldThrowBusyAfterBoundedWait() {
        when(sync.set(anyString(), anyString(), any())).thenReturn(null);
        long t0 = System.currentTimeMillis();

        SandboxEnvException e = assertThrows(SandboxEnvException.class, () -> lock.acquire("sbx-1"));
        assertEquals("env_update_busy", e.getErrorCode());
        assertEquals(503, e.getHttpStatus());

        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed >= 250, "应等满注入的等待窗口再拒绝，实际 " + elapsed + "ms");
        assertTrue(elapsed < 5_000, "等待必须有限（503 而非无限排队），实际 " + elapsed + "ms");
    }

    @Test
    void acquireShouldFailOpenWhenRedisErrors() {
        // 锁是正确性增强而非可用性单点：Redis 异常必须放行（noop 租约，写序修复独立成立）
        when(sync.set(anyString(), anyString(), any())).thenThrow(new RedisException("connection refused"));

        RedisSandboxEnvLock.Lease lease = lock.acquire("sbx-1");
        assertSame(RedisSandboxEnvLock.Lease.NOOP, lease, "fail-open 应返回 noop 租约");
        lease.close(); // noop close 幂等无害
    }

    @Test
    void releaseFailureShouldBeSwallowed() {
        when(sync.set(anyString(), anyString(), any())).thenReturn("OK");
        when(sync.eval(anyString(), any(ScriptOutputType.class), any(String[].class), anyString()))
            .thenThrow(new RedisException("connection gone"));

        lock.acquire("sbx-1").close(); // 释放失败不抛：不再续租，TTL 到期自愈
    }

    // ---------- 续租（评审 g1：持有期间周期续租，互斥不依赖 TTL 覆盖临界区耗时）----------

    @Test
    void renewShouldPexpireByTokenAndReportHeld() {
        // token 匹配 → pexpire 成功返回 1 → 仍在持有
        when(sync.eval(eq(RedisSandboxEnvLock.RENEW_LUA), eq(ScriptOutputType.INTEGER),
            aryEq(new String[]{"pfx:sbx:env:sbx-1"}), eq("tok"), eq(String.valueOf(RedisSandboxEnvLock.LEASE_TTL_MS))))
            .thenReturn(1L);
        assertTrue(lock.renew("pfx:sbx:env:sbx-1", "tok"));
    }

    @Test
    void renewShouldReportLostWhenTokenMismatchOrKeyGone() {
        // token 不匹配 / 键已过期删除（Lua 返回 0）→ 报告未持有（不抛、不复活他人租约）
        when(sync.eval(eq(RedisSandboxEnvLock.RENEW_LUA), eq(ScriptOutputType.INTEGER),
            any(String[].class), anyString(), anyString())).thenReturn(0L);
        assertFalse(lock.renew("pfx:sbx:env:sbx-1", "tok"));
    }

    @Test
    void renewShouldNotThrowWhenRedisErrors() {
        // 续租失败按未持有处理（调度线程内不能外溢异常），TTL 内下轮重试
        when(sync.eval(anyString(), any(ScriptOutputType.class), any(String[].class), anyString(), anyString()))
            .thenThrow(new RedisException("connection gone"));
        assertFalse(lock.renew("pfx:sbx:env:sbx-1", "tok"));
    }

    @Test
    void blankSandboxIdShouldFailOpenWithoutTouchingRedis() {
        // 防御：空 id 会退化为全体共享一把锁 → 宁可无锁也不引入跨沙箱互斥
        assertSame(RedisSandboxEnvLock.Lease.NOOP, lock.acquire(" "));
        assertSame(RedisSandboxEnvLock.Lease.NOOP, lock.acquire(null));
        verifyNoInteractions(sync);
        verify(facade, never()).key(anyString());
    }

    @Test
    void noopLockShouldReturnNoopLeaseWithoutRedis() {
        RedisSandboxEnvLock noop = RedisSandboxEnvLock.noop();
        RedisSandboxEnvLock.Lease lease = noop.acquire("sbx-1");
        assertSame(RedisSandboxEnvLock.Lease.NOOP, lease);
        lease.close();
    }
}
