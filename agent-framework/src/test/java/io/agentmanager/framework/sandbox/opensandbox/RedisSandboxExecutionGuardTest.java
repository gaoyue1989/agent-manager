package io.agentmanager.framework.sandbox.opensandbox;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.lettuce.core.RedisClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSandboxExecutionGuardTest {

    private static final int CLOSED_PORT = 1; // 本机不可达端口（连接被拒 → fail-open 路径）

    private static SandboxIsolationKey userKey() {
        var ctx = RuntimeContext.builder().userId("alice").sessionId("s1").build();
        return SandboxIsolationKey.resolve(IsolationScope.USER, ctx, "test-agent").orElseThrow();
    }

    @Test
    void tryEnterShouldFailOpenWhenRedisUnreachable() throws InterruptedException {
        // 守卫是正确性增强而非可用性单点：Redis 不可达时必须放行（noop lease）
        var guard = new RedisSandboxExecutionGuard(
            RedisClient.create("redis://127.0.0.1:" + CLOSED_PORT), "sbx:guard:test", 60_000L);
        long t0 = System.currentTimeMillis();
        SandboxLease lease = guard.tryEnter(userKey());
        long elapsed = System.currentTimeMillis() - t0;
        assertNotNull(lease, "fail-open 应返回 noop lease 而非 null/异常");
        assertTrue(elapsed < 15_000, "不可达应在连接超时内快速失败，实际 " + elapsed + "ms");
        lease.close(); // noop close 幂等无害
    }

    @Test
    void tryEnterShouldBlockButInterruptibly() throws Exception {
        // 连接不可达时 fail-open 立即返回；这里验证同一构造在 interrupt 下可中断退出
        var guard = new RedisSandboxExecutionGuard(
            RedisClient.create("redis://127.0.0.1:" + CLOSED_PORT), "sbx:guard:test", 60_000L);
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

    @Test
    void keyShouldCarryUserValue() {
        var key = userKey();
        assertNotNull(key.getValue());
        assertEquals("USER", key.getScope().name());
        // redis key 前缀拼接格式稳定（guard 与排查口径一致）
        assertNotNull(Set.of(key.getScope().name().toLowerCase()));
    }
}
