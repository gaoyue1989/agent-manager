package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * BoundedLruMap 单测：容量淘汰命中最久未访问条目、读/写均刷新顺序、并发访问安全。
 */
class BoundedLruMapTest {

    @Test
    void mustEvictLeastRecentlyAccessedEntry() {
        Map<String, Integer> map = BoundedLruMap.of(3);
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);
        map.get("a");
        map.put("d", 4);
        assertEquals(3, map.size());
        assertFalse(map.containsKey("b"), "get(a) 后 b 是最久未访问，必须被淘汰");
        assertTrue(map.containsKey("a"));
        assertTrue(map.containsKey("c"));
        assertTrue(map.containsKey("d"));
    }

    @Test
    void mustRefreshOrderOnUpdateOfExistingKey() {
        Map<String, Integer> map = BoundedLruMap.of(2);
        map.put("a", 1);
        map.put("b", 2);
        map.put("a", 10);
        map.put("c", 3);
        assertEquals(2, map.size());
        assertFalse(map.containsKey("b"));
        assertEquals(10, map.get("a"));
    }

    @Test
    void mustStayConcurrentSafeUnderContention() throws Exception {
        Map<Integer, Integer> map = BoundedLruMap.of(200);
        var gate = new CountDownLatch(1);
        var threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            final int base = t;
            threads[t] = Thread.ofPlatform().start(() -> {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                }
                for (int i = 0; i < 5000; i++) {
                    map.put(base * 5000 + i, i);
                    map.get(base * 5000 + i);
                }
            });
        }
        gate.countDown();
        for (var thread : threads) {
            thread.join(10_000);
        }
        assertTrue(map.size() <= 200, "并发写入后必须仍满足容量上限：实际 " + map.size());
    }
}
