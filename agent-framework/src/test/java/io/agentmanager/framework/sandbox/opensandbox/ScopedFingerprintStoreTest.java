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
 * ScopedFingerprintStore 契约（first-llm-latency 缺口④共享层）：
 * <ul>
 *   <li>get 捕 RedisException → fail-open 返回 null（调用方回落 Pod 级表走慢路径，不出正确性问题）</li>
 *   <li>put 吞异常（漏写的后果=他副本多走一次慢路径，与无共享层等价）</li>
 *   <li>setex TTL=604800s（7 天，沙箱代生命周期上界防泄漏积累）+ key 布局 sbx:scopedfp:{sandboxId}|{scope}</li>
 * </ul>
 */
class ScopedFingerprintStoreTest {

    @Test
    void getShouldFailOpenAndPutShouldSwallowOnRedisFailure() {
        var sync = mock(RedisClusterCommands.class);
        when(sync.get(anyString())).thenThrow(new RedisException("connection down"));
        doThrow(new RedisException("connection down")).when(sync).setex(anyString(), anyLong(), anyString());
        var facade = mock(io.agentmanager.framework.redis.RedisConnectionFacade.class);
        when(facade.sync()).thenReturn(sync);
        var store = new ScopedFingerprintStore(facade);

        assertNull(store.get("sbx-1", "sess-A"),
            "Redis 不可达 get 必须 fail-open 返回 null（未知走慢路径），不得抛出污染调用方");

        assertDoesNotThrow(() -> store.put("sbx-1", "sess-A", "fp"),
            "put 失败仅告警，不得抛出（投放主路径不受共享层故障影响）");
    }

    @Test
    void putShouldSetexWithSevenDayTtlAndStableKeyLayout() {
        var data = new HashMap<String, String>();
        var sync = mock(RedisClusterCommands.class);
        doAnswer(inv -> {
            data.put((String) inv.getArgument(0), (String) inv.getArgument(2));
            return "OK";
        }).when(sync).setex(anyString(), anyLong(), anyString());
        var facade = mock(io.agentmanager.framework.redis.RedisConnectionFacade.class);
        when(facade.sync()).thenReturn(sync);
        var store = new ScopedFingerprintStore(facade);

        store.put("sbx-1", "sess-A", "fp-value");

        var keyCap = ArgumentCaptor.forClass(String.class);
        var ttlCap = ArgumentCaptor.forClass(Long.class);
        var valCap = ArgumentCaptor.forClass(String.class);
        verify(sync).setex(keyCap.capture(), ttlCap.capture(), valCap.capture());
        assertEquals("sbx:scopedfp:sbx-1|sess-A", keyCap.getValue(), "key 布局必须稳定（调用方 get 同构拼 key）");
        assertEquals(604800L, ttlCap.getValue(), "TTL 必须 7 天=604800s（沙箱代生命周期上界，防泄漏积累）");
        assertEquals("fp-value", valCap.getValue());
        assertEquals("fp-value", data.get("sbx:scopedfp:sbx-1|sess-A"), "get/put 读写对称性");
    }
}
