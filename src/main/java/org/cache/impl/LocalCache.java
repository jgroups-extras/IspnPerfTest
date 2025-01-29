package org.cache.impl;

import org.cache.Cache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purely local cache
 * @author Bela Ban
 * @since x.y
 */
public class LocalCache<K,V> implements Cache<K,V> {
    protected final Map<K,V> map=new ConcurrentHashMap<>();

    @Override
    public V put(K key, V value) {
        return map.put(key, value);
    }

    @Override
    public V get(K key) {
        return map.get(key);
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public Set<K> keySet() {
        return map.keySet();
    }

    @Override
    public Map<K,V> getContents() {
        return map;
    }
}
