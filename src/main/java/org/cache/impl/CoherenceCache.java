package org.cache.impl;

import com.tangosol.net.NamedCache;
import org.cache.Cache;

import java.util.Map;
import java.util.Set;

/**
 * Remove .txt suffix to compile
 * @author Bela Ban
 * @since x.y
 */
public class CoherenceCache<K,V> implements Cache<K,V> {
    protected final NamedCache<K,V> cache;

    public CoherenceCache(NamedCache<K,V> cache) {
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

    public Map<K,V> getContents() {
        throw new UnsupportedOperationException();
    }
}
