package io.lolyay.gma4j.net.util;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.function.Function;

public final class CowMap<K, V> {

    private volatile Object2ObjectMap<K, V> map = Object2ObjectMaps.emptyMap();
    private final Object writeLock = new Object();

    public V computeIfAbsent(K key, Function<? super K, ? extends V> mapping) {
        V existing = map.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (writeLock) {
            existing = map.get(key);
            if (existing != null) {
                return existing;
            }
            V created = mapping.apply(key);
            Object2ObjectOpenHashMap<K, V> next = new Object2ObjectOpenHashMap<>(map);
            next.put(key, created);
            next.trim();
            map = next;
            return created;
        }
    }
}
