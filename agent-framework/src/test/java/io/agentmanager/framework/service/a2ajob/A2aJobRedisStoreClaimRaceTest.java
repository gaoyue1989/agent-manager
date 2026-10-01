package io.agentmanager.framework.service.a2ajob;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * claim 的 NX→GET 过期竞态分型（修评审 #73）：键在 SET 与 GET 之间过期时 GET 返回
 * null ≠ 被占用，必须原地重试 NX 而非视作认领成功——否则双副本并发发送，打破同键
 * 恰好一次；竞态持续不可判定则抛出，由上层 fail-closed 503。
 */
class A2aJobRedisStoreClaimRaceTest {

    private RedisConnectionFacade facade;
    @SuppressWarnings("unchecked")
    private final RedisClusterCommands<String, String> sync = mock(RedisClusterCommands.class);
    private A2aJobRedisStore store;

    @BeforeEach
    void setUp() {
        facade = mock(RedisConnectionFacade.class);
        when(facade.key(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(facade.sync()).thenReturn(sync);
        store = new A2aJobRedisStore(facade);
    }

    @Test
    void expiredKeyBetweenNxAndGetShouldRetryNxAndWin() {
        // 第 1 轮：NX 失败（null）+ GET null（键恰好过期）；第 2 轮 NX 成功（"OK"）→ 认领成功
        when(sync.set(anyString(), anyString(), any())).thenReturn(null).thenReturn("OK");
        when(sync.get(anyString())).thenReturn(null);
        assertNull(store.claim("k1", "tok", Duration.ofSeconds(10)));
    }

    @Test
    void expiredKeyShouldReturnOccupiedValueOnceVisible() {
        // 第 1 轮 GET null，第 2 轮 NX 失败 + GET 命中 → 返回占用值（409/幂等判定不变）
        when(sync.set(anyString(), anyString(), any())).thenReturn(null).thenReturn(null);
        when(sync.get(anyString())).thenReturn(null).thenReturn("claim:other");
        assertEquals("claim:other", store.claim("k2", "tok", Duration.ofSeconds(10)));
    }

    @Test
    void persistentRaceShouldThrowForFailClosedUpperLayer() {
        // 三轮 NX 失败且 GET 恒 null（防御路径）：抛出由上层映射 JobUnavailableException
        when(sync.set(anyString(), anyString(), any())).thenReturn(null);
        when(sync.get(anyString())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> store.claim("k3", "tok", Duration.ofSeconds(10)));
        // 钉死重试上界：恰好 3 次 NX + 3 次 GET，不无限空转
        verify(sync, times(3)).set(anyString(), anyString(), any());
        verify(sync, times(3)).get(anyString());
    }
}
