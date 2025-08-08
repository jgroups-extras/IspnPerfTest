package org.cache.impl;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.jgroups.util.Util;

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

    public Cache<K,V> create(String cache_name, String ignored) {
        org.infinispan.Cache<K,V> cache=mgr.getCache(cache_name);
        // for a put(), we don't need the previous value
        return new InfinispanCache<>(cache);
    }

    @ViewChanged
    public static void viewChanged(ViewChangedEvent evt) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(evt.getViewId());
        if (evt.getNewMembers() != null) {
            sb.append(" (").append(evt.getNewMembers().size()).append(")");
            sb.append(" [").append(Util.printListWithDelimiter(evt.getNewMembers(), ", ", Util.MAX_LIST_PRINT_SIZE)).append("]");
        }
        System.out.println("** view: " + sb);
    }
}
