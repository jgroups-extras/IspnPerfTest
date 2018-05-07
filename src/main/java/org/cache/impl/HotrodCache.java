package org.cache.impl;

import org.cache.Cache;
import org.infinispan.client.hotrod.RemoteCache;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author Bela Ban
 * @since x.y
 */
public class HotrodCache<K,V> implements Cache<K,V> {
    protected final RemoteCache<K,V> rc;

    public HotrodCache(RemoteCache<K,V> rc) {
        this.rc=rc;
    }

    public V put(K key, V value) {
        return rc.put(key, value);
    }

    public V get(K key) {
        return rc.get(key);
    }

    public void clear() {
        rc.clear();
    }

    public int size() {
        return rc.size();
    }

    public boolean isEmpty() {
        return rc.isEmpty();
    }

    public Set<K> keySet() {
        return rc.keySet();
    }

    public Map<K,V> getContents() {
        Map<K,V> contents=new HashMap<>();
        for(Map.Entry<K,V> entry: rc.entrySet())
            contents.put(entry.getKey(), entry.getValue());
        return contents;
    }
}
