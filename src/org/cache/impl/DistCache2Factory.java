package org.cache.impl;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.jgroups.util.Util;

/**
 * @author Bela Ban
 * @since x.y
 */
public class DistCache2Factory<K,V> implements CacheFactory<K,V> {
    protected String     config;
    protected DistCache2 cache;

    public void init(String config) throws Exception {
        this.config=config;
    }

    public void destroy() {
        Util.close(cache);
    }

    public Cache<K,V> create(String cache_name) {
        try {
            cache=new DistCache2(config);
            return cache;
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}
