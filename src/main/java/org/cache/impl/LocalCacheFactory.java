package org.cache.impl;

import org.cache.Cache;
import org.cache.CacheFactory;

/**
 * @author Bela Ban
 * @since x.y
 */
public class LocalCacheFactory<K,V> implements CacheFactory<K,V> {
    @Override
    public void init(String config) throws Exception {
        ;
    }

    @Override
    public void destroy() {
        ;
    }

    @Override
    public Cache<K,V> create(String ignore, String ignored) {
        return new LocalCache<>();
    }
}
