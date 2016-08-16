package org.cache.impl;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.jgroups.blocks.ReplCache;

/**
 * @author Bela Ban
 * @since x.y
 */
public class JGroupsCacheFactory<K,V> implements CacheFactory<K,V> {
    protected String         cfg;
    protected ReplCache<K,V> cache;

    public JGroupsCacheFactory() {
    }

    public void init(String config) throws Exception {
        cfg=config;
    }

    public void destroy() {
        if(cache != null) {
            cache.stop();
            cache=null;
        }
    }

    public Cache<K,V> create(String cache_name) {
        try {
            cache=new ReplCache<>(cfg, "jg");
            cache.setCachingTime(0);
            cache.setDefaultReplicationCount((short)2);
            cache.setCallTimeout(10000);
            cache.start();
            return new JGroupsCache<>(cache);
        }
        catch(Exception e) {
            throw new RuntimeException("failed creating JGroups cache", e);
        }
    }
}
