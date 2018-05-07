package org.cache.impl;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;

/**
 * CacheFactory which uses a remote Infinispan server via Hotrod
 * @author Bela Ban
 * @since x.y
 */
@SuppressWarnings("unused")
// @Listener
public class HotrodCacheFactory<K,V> implements CacheFactory<K,V> {
    protected RemoteCacheManager remoteCacheManager;

    /** Empty constructor needed for an instance to be created via reflection */
    public HotrodCacheFactory() {
    }

    /**
     * Initializes the remote cache manager
     * @param config The syntax is a semicolon-separated list of IP addresses and ports, e.g. "hostA:12345;1.2.3.4:5555"
     * @throws Exception
     */
    public void init(String config) throws Exception {
        org.infinispan.client.hotrod.configuration.ConfigurationBuilder cb=new ConfigurationBuilder()
          .addServers(config);
        remoteCacheManager=new RemoteCacheManager(cb.build());
    }

    public void destroy() {
    }

    public Cache<K,V> create(String cache_name) {
        RemoteCache<K,V> cache=remoteCacheManager.getCache(cache_name);
        return new HotrodCache(cache);
    }

    /*@ViewChanged
    public static void viewChanged(ViewChangedEvent evt) {
        Transport transport=evt.getCacheManager().getTransport();
        if(transport instanceof JGroupsTransport) {
            View view=((JGroupsTransport)transport).getChannel().getView();
            System.out.println("** view: " + view);
        }
        else
            System.out.println("** view: " + evt);
    }*/
}
