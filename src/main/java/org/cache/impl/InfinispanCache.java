package org.cache.impl;

import org.cache.Cache;

import java.util.Set;

/**
 * @author Bela Ban
 * @since x.y
 */
public class InfinispanCache<K,V> implements Cache<K,V> {
    protected final org.infinispan.Cache<K,V> cache;

    public InfinispanCache(org.infinispan.Cache<K,V> cache) {
        this.cache=cache;
    }

    public V put(K key, V value) {
        return cache.put(key, value);
    }

    public V get(K key) {
        return cache.get(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public boolean isEmpty() {
        return cache.isEmpty();
    }

    public Set<K> keySet() {
        return cache.keySet();
    }
}
