package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

/**
 * ScopedFingerprintStore 契约（first-llm-latency 缺口④共享层，issue #393 单槽语义）：
 * <ul>
 *   <li>key 布局 = 沙箱代单槽 {@code sbx:scopedfp:{sandboxId}}（经 facade.key 统一加前缀），
 *       值 = {@code scope|mode|指纹}，put 原子覆盖——容器内 prune「同代只存一个有效 scope」的
 *       物理事实对齐：旧 scope 被覆盖后其他副本不得再凭陈旧记录伪造稳态跳过收敛</li>
 *   <li>get 按首个 | 拆分：槽内 scope 段≠请求 scope 返回 null；指纹段可含 |（farm 指纹
 *       =version|revision），余段不二拆</li>
 *   <li>get 捕 RedisException → fail-open 返回 null（调用方回落 Pod 级表走慢路径，不出正确性问题）</li>
 *   <li>put 吞异常（漏写的后果=他副本多走一次慢路径，与无共享层等价）</li>
 *   <li>setex TTL=604800s（7 天，沙箱代生命周期上界防泄漏积累）</li>
 * </ul>
 */
class ScopedFingerprintStoreTest {

    @SuppressWarnings("unchecked")
    private static RedisClusterCommands<String, String> mapBackedSync(Map<String, String> data) {
        var sync = mock(RedisClusterCommands.class);
        when(sync.get(anyString())).thenAnswer(inv -> data.get((String) inv.getArgument(0)));
        doAnswer(inv -> {
            data.put((String) inv.getArgument(0), (String) inv.getArgument(2));
            return "OK";
        }).when(sync).setex(anyString(), anyLong(), anyString());
        return sync;
    }

    private static io.agentmanager.framework.redis.RedisConnectionFacade identityKeyFacade(
            RedisClusterCommands<String, String> sync) {
        var facade = mock(io.agentmanager.framework.redis.RedisConnectionFacade.class);
        when(facade.sync()).thenReturn(sync);
        when(facade.key(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return facade;
    }

    @Test
    void getShouldFailOpenAndPutShouldSwallowOnRedisFailure() {
        var sync = mock(RedisClusterCommands.class);
        when(sync.get(anyString())).thenThrow(new RedisException("connection down"));
        doThrow(new RedisException("connection down")).when(sync).setex(anyString(), anyLong(), anyString());
        var facade = mock(io.agentmanager.framework.redis.RedisConnectionFacade.class);
        when(facade.sync()).thenReturn(sync);
        when(facade.key(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var store = new ScopedFingerprintStore(facade);

        assertNull(store.get("sbx-1", "sess-A"),
            "Redis 不可达 get 必须 fail-open 返回 null（未知走慢路径），不得抛出污染调用方");

        assertDoesNotThrow(() -> store.put("sbx-1", "sess-A", "fp"),
            "put 失败仅告警，不得抛出（投放主路径不受共享层故障影响）");
    }

    @Test
    void putShouldSetexWithSevenDayTtlAndSingleSlotLayout() {
        var data = new HashMap<String, String>();
        var sync = mapBackedSync(data);
        var store = new ScopedFingerprintStore(identityKeyFacade(sync));

        store.put("sbx-1", "sess-A", "fp-value");

        var keyCap = ArgumentCaptor.forClass(String.class);
        var ttlCap = ArgumentCaptor.forClass(Long.class);
        var valCap = ArgumentCaptor.forClass(String.class);
        verify(sync).setex(keyCap.capture(), ttlCap.capture(), valCap.capture());
        assertEquals("sbx:scopedfp:sbx-1", keyCap.getValue(), "key 布局 = 沙箱代单槽（无 scope 维度）");
        assertEquals(604800L, ttlCap.getValue(), "TTL 必须 7 天=604800s（沙箱代生命周期上界，防泄漏积累）");
        assertEquals("sess-A|fp-value", valCap.getValue(), "值 = {scope}|{记账值}（记账值含 mode 分量）");
        assertEquals("sess-A|fp-value", data.get("sbx:scopedfp:sbx-1"), "get/put 读写对称性");
    }

    @Test
    void singleSlotOverwriteInvalidatesStaleScope() {
        var data = new HashMap<String, String>();
        var store = new ScopedFingerprintStore(identityKeyFacade(mapBackedSync(data)));

        store.put("sbx-1", "sess-A", "farm|fp-a");
        store.put("sbx-1", "sess-B", "farm|fp-b");

        assertEquals(1, data.size(), "同沙箱代只有一个槽（prune 掉旧 scope 时槽被新 scope 原子覆盖）");
        assertEquals("sess-B|farm|fp-b", data.get("sbx:scopedfp:sbx-1"));
        assertNull(store.get("sbx-1", "sess-A"),
            "单槽被 scope-B 覆盖后 scope-A 陈旧记录不得命中（其他副本凭它伪造稳态即 issue #393）");
        assertEquals("farm|fp-b", store.get("sbx-1", "sess-B"));
    }

    @Test
    void fingerprintContainingPipeShouldRoundTripLosslessly() {
        var data = new HashMap<String, String>();
        var store = new ScopedFingerprintStore(identityKeyFacade(mapBackedSync(data)));

        store.put("sbx-1", "sess-A", "v-3|rev-9");

        assertEquals("v-3|rev-9", store.get("sbx-1", "sess-A"),
            "指纹段可含 |（farm 指纹=version|revision）：get 按首个 | 拆 scope，余段不二拆");
    }
}
