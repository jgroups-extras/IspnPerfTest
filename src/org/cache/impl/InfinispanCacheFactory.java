package org.cache.impl;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.infinispan.context.Flag;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;

/**
 * @author Bela Ban
 * @since x.y
 */
@SuppressWarnings("unused")
@Listener
public class InfinispanCacheFactory<K,V> implements CacheFactory<K,V> {
    protected EmbeddedCacheManager mgr;

    /** Empty constructor needed for an instance to be created via reflection */
    public InfinispanCacheFactory() {
    }

    public void init(String config) throws Exception {
        mgr=new DefaultCacheManager(config);
        mgr.addListener(this);
    }

    public void destroy() {
        mgr.stop();
    }

    public Cache<K,V> create(String cache_name) {
        org.infinispan.Cache<K,V> cache=mgr.getCache(cache_name);
        // for a put(), we don't need the previous value
        cache=cache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES);
        return new InfinispanCache(cache);
    }

    @ViewChanged
    public static void viewChanged(ViewChangedEvent evt) {
        System.out.printf("** view: %s joined %s left\n", evt.getNewMembers(), evt.getOldMembers());
    }
}
