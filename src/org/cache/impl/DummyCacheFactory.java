package org.cache.impl;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.jgroups.util.Util;

/**
 * @author Bela Ban
 * @since  1.0
 */
public class DummyCacheFactory<K,V> implements CacheFactory<K,V> {
    protected String     config;
    protected DummyCache cache;

    public void init(String config) throws Exception {
        this.config=config;
    }

    public void destroy() {
        Util.close(cache);
    }

    public Cache<K,V> create(String cache_name) {
        try {
            cache=new DummyCache(config);
            return cache;
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}
