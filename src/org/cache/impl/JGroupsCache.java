package org.cache.impl;

import org.cache.Cache;
import org.jgroups.blocks.ReplCache;

import java.util.Set;

/**
 * Cache wrapper using {@link org.jgroups.blocks.ReplCache}
 * @author Bela Ban
 * @since x.y
 */
public class JGroupsCache<K,V> implements Cache<K,V> {
    protected final ReplCache<K,V> cache;

    public JGroupsCache(ReplCache<K,V> cache) {
        this.cache=cache;
    }

    public V put(K key, V value) {
        cache.put(key, value, (short)2, 10000, true);
        return null;
    }

    public V get(K key) {
        return cache.get(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.getL2Cache().getSize();
    }

    public boolean isEmpty() {
        return cache.getL2Cache().getSize() == 0;
    }

    public Set<K> keySet() {
        return cache.getL2Cache().getInternalMap().keySet();
    }
}
