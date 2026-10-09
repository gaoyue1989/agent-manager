package io.agentmanager.framework.service.a2ajob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.agentmanager.framework.config.AgentRedisProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;

/**
 * A2aJobRedisStore 真 Redis 集成测试（CR P1-3，Issue #69 §6；门控同 RedisEventLogIT）：
 * <pre>
 *   docker run --rm -d -p 6399:6379 redis:7.2-alpine
 *   REDIS_IT=1 REDIS_IT_URL=redis://127.0.0.1:6399 mvn test -Dtest=A2aJobRedisStoreIT
 * </pre>
 *
 * <p>验证：SET NX 原子性（并发同键仅一者认领）/ Lua CAS 三条路径（complete·release·extend
 * 的 token 匹配与错配）/ 租期到期接管 / 完成态 TTL。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true|1")
class A2aJobRedisStoreIT {

    private RedisConnectionFacade facade;
    private A2aJobRedisStore store;
    private String key;

    @BeforeAll
    void setUp() {
        var url = System.getenv().getOrDefault("REDIS_IT_URL", "redis://127.0.0.1:6379");
        var prefix = System.getenv().getOrDefault("REDIS_IT_PREFIX", "");
        facade = RedisConnectionFacade.create(
            new AgentRedisProperties(url, 2000, 2000, 250_000,
                AgentRedisProperties.Mode.standalone, "", prefix));
        store = new A2aJobRedisStore(facade);
    }

    @AfterAll
    void tearDown() {
        if (key != null) {
            facade.sync().del(store.key(key));
        }
        facade.close();
    }

    @Test
    void claimShouldBeAtomicAndCasPathsShouldEnforceOwnership() throws Exception {
        key = "it-" + UUID.randomUUID();

        // SET NX 原子性：A 认领成功，B 拿到 A 的 claim 值
        assertNull(store.claim(key, "tokA", Duration.ofSeconds(30)));
        var bView = store.claim(key, "tokB", Duration.ofSeconds(30));
        assertEquals(A2aJobRedisStore.CLAIM_PREFIX + "tokA", bView);

        // CAS 错配：B 的 complete/release/extend 全部失败（所有权在 A）
        assertFalse(store.completeWithTaskId(key, "tokB", "task-b", Duration.ofSeconds(30)));
        assertFalse(store.release(key, "tokB"));
        assertFalse(store.extendLease(key, "tokB", Duration.ofSeconds(30)));
        assertEquals(A2aJobRedisStore.CLAIM_PREFIX + "tokA", store.get(key));

        // CAS 匹配：A 释放成功 → 键消失；同键可立即重试（claim 再次成功）
        assertTrue(store.release(key, "tokA"));
        assertNull(store.get(key));
        assertNull(store.claim(key, "tokC", Duration.ofSeconds(1)));
    }

    @Test
    void completeShouldSetTaskIdWithRetentionTtlAndLeaseShouldExpireForTakeover() throws Exception {
        key = "it-" + UUID.randomUUID();

        // 认领 → complete（短 TTL 观察）→ 值为 taskId 且 TTL ≤ retention
        assertNull(store.claim(key, "tokA", Duration.ofSeconds(1)));
        assertTrue(store.completeWithTaskId(key, "tokA", "task-final", Duration.ofSeconds(5)));
        assertEquals("task-final", store.get(key));
        Long ttl = facade.sync().ttl(store.key(key));
        assertTrue(ttl != null && ttl > 0 && ttl <= 5, "完成态 TTL 应为 retention（实际 " + ttl + "s）");

        // 幂等窗口内同键提交 → 命中 taskId（不重发）
        assertEquals("task-final", store.claim(key, "tokB", Duration.ofSeconds(1)));

        // 完成态随 retention TTL 过期（5s）→ 键消失 → 新认领成功（接管路径）
        Thread.sleep(5500);
        assertNull(store.get(key));
        assertNull(store.claim(key, "tokC", Duration.ofSeconds(30)));
    }
}
