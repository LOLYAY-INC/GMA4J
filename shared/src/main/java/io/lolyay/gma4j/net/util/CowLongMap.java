package io.lolyay.gma4j.net.util;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.function.ToLongFunction;

final class CowLongMap<K> {

    private static final long ABSENT = Long.MIN_VALUE;

    private volatile Object2LongMap<K> map = newMap();
    private final Object writeLock = new Object();

    private static <K> Object2LongOpenHashMap<K> newMap() {
        Object2LongOpenHashMap<K> m = new Object2LongOpenHashMap<>();
        m.defaultReturnValue(ABSENT);
        return m;
    }

    long computeIfAbsent(K key, ToLongFunction<? super K> mapping) {
        long existing = map.getLong(key);
        if (existing != ABSENT) {
            return existing;
        }
        synchronized (writeLock) {
            Object2LongMap<K> current = map;
            if (current.containsKey(key)) {
                return current.getLong(key);
            }
            long created = mapping.applyAsLong(key);
            Object2LongOpenHashMap<K> next = new Object2LongOpenHashMap<>(current);
            next.defaultReturnValue(ABSENT);
            next.put(key, created);
            next.trim();
            map = next;
            return created;
        }
    }
}
