package org.cache.impl;

import com.tangosol.net.NamedCache;
import org.cache.Cache;
import org.cache.CacheFactory;
import org.infinispan.notifications.Listener;

/**
 * @author Bela Ban
 * @since x.y
 */
@SuppressWarnings("unused")
@Listener
public class CoherenceCacheFactory<K,V> implements CacheFactory<K,V> {

    /** Empty constructor needed for an instance to be created via reflection */
    public CoherenceCacheFactory() {
    }

    public void init(String config) throws Exception {
        com.tangosol.net.CacheFactory.ensureCluster();
    }

    public void destroy() {
        com.tangosol.net.CacheFactory.shutdown();
    }

    public Cache<K,V> create(String cache_name) {
        return new CoherenceCache<>((NamedCache<K,V>)com.tangosol.net.CacheFactory.getCache(cache_name));
    }


}
