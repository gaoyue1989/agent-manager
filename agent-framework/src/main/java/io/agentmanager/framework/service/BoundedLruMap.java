package io.agentmanager.framework.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class BoundedLruMap {

    private BoundedLruMap() {
    }

    static <K, V> Map<K, V> of(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        });
    }
}
