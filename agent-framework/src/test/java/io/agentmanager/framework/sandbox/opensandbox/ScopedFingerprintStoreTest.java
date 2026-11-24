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

    @Test
    void slotScopeShouldReturnAuthoritativeScopeSegment() {
        var data = new HashMap<String, String>();
        var store = new ScopedFingerprintStore(identityKeyFacade(mapBackedSync(data)));

        assertNull(store.slotScope("sbx-1"), "槽缺失=未知，不得虚构现役 scope");

        store.put("sbx-1", "sess-A", "farm|fp-a");
        assertEquals("sess-A", store.slotScope("sbx-1"));

        store.put("sbx-1", "sess-B", "farm|fp-b");
        assertEquals("sess-B", store.slotScope("sbx-1"), "槽覆盖后现役 scope 随之切换（跨副本裁决值）");

        assertNull(store.slotScope("sbx-missing"), "无记录沙箱代=未知");
        assertNull(ScopedFingerprintStore.NOOP.slotScope("sbx-1"), "未装配=未知");
    }

    @Test
    void slotScopeShouldFailOpenOnRedisFailure() {
        var sync = mock(RedisClusterCommands.class);
        when(sync.get(anyString())).thenThrow(new RedisException("connection down"));
        var facade = mock(io.agentmanager.framework.redis.RedisConnectionFacade.class);
        when(facade.sync()).thenReturn(sync);
        when(facade.key(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var store = new ScopedFingerprintStore(facade);

        assertNull(store.slotScope("sbx-1"),
            "Redis 不可达必须 fail-open 返回 null（未知不动 Pod 级信念，不得清除本地记录）");
    }

    @Test
    void markPrunedShouldOverwriteForeignScopeSlotWithTombstone() {
        // issue #481（复现 01a 根因②）：ERR- prune 执行副本从未收敛过被删 scope → 本地
        // stale 表为空，槽仍残留记账副本写入的记录 → 端点恢复后陈旧稳态锁死。markPruned
        // 不依赖本 Pod 本地记账，直接把单槽覆写为 keepScope 墓碑（非空值权威广播）
        var data = new HashMap<String, String>();
        var store = new ScopedFingerprintStore(identityKeyFacade(mapBackedSync(data)));
        store.put("sbx-1", "sess-A", "farm|fp-a");

        store.markPruned("sbx-1", "sess-B");

        assertEquals("sess-B|__pruned__|", data.get("sbx:scopedfp:sbx-1"),
            "槽被他 scope 占据必须覆写为非空墓碑而非 DEL（DEL 的缺失被 fail-open 混同为不可达，#481 根因③）");
        assertEquals("sess-B", store.slotScope("sbx-1"), "墓碑现役 scope 段 = keepScope（跨副本权威裁决值）");
        assertNull(store.get("sbx-1", "sess-A"), "被 prune scope 的记账必须失效");
        assertEquals("__pruned__|", store.get("sbx-1", "sess-B"),
            "keepScope 墓碑记账模式段不匹配 farm：端点恢复后 keepScope 自身也必须重收敛");
    }

    @Test
    void markPrunedShouldKeepBookkeepingWhenSlotAlreadyPointsAtKeepScope() {
        // 收敛周期内 record 先于 pruneExcept 写槽（SkillsFarm 时序）：槽已指向 keepScope 的
        // 正常 farm 记账 = 容器实态（pruneCmd 保留 keepScope 目录），markPruned 不得覆盖——
        // 否则稳态短路被墓碑击穿，每次收敛后立即失效
        var data = new HashMap<String, String>();
        var store = new ScopedFingerprintStore(identityKeyFacade(mapBackedSync(data)));
        store.put("sbx-1", "sess-B", "farm|fp-b");

        store.markPruned("sbx-1", "sess-B");

        assertEquals("sess-B|farm|fp-b", data.get("sbx:scopedfp:sbx-1"),
            "keepScope 正常记账必须保留（稳态正确性由指纹比对承担）");
    }

    @Test
    void markPrunedShouldFillMissingSlotAndSwallowRedisFailure() {
        var data = new HashMap<String, String>();
        var store = new ScopedFingerprintStore(identityKeyFacade(mapBackedSync(data)));

        store.markPruned("sbx-1", "sess-B");
        assertEquals("sess-B|__pruned__|", data.get("sbx:scopedfp:sbx-1"),
            "槽缺失同样落墓碑：缺失=fail-open 未知正是 #481 锁死链的一环，prune 后不得再出现槽缺失态");

        assertDoesNotThrow(() -> ScopedFingerprintStore.NOOP.markPruned("sbx-1", "sess-B"),
            "未装配 = no-op");

        var sync = mock(RedisClusterCommands.class);
        when(sync.get(anyString())).thenThrow(new RedisException("connection down"));
        doThrow(new RedisException("connection down")).when(sync).setex(anyString(), anyLong(), anyString());
        var broken = new ScopedFingerprintStore(identityKeyFacade(sync));
        assertDoesNotThrow(() -> broken.markPruned("sbx-1", "sess-B"),
            "Redis 不可达仅告警，不得抛出（prune 主路径不受共享层故障影响）");
    }
}
