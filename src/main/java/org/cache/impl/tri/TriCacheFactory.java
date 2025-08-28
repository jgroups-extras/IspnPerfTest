package org.cache.impl.tri;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.jgroups.util.Util;

/**
 * @author Bela Ban
 * @since  1.0
 */
public class TriCacheFactory<K,V> implements CacheFactory<K,V> {
    protected String        config;
    protected TriCache<K,V> cache;

    public void init(String config, boolean metricsEnabled, int metricsPort) throws Exception {
        this.config=config;
    }

    public void destroy() {
        Util.close(cache);
    }

    public Cache<K,V> create(String cache_name, String name) {
        try {
            cache=new TriCache<>(config, name);
            return cache;
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}
