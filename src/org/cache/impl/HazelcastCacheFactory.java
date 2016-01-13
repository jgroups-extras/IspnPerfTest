package org.cache.impl;

import com.hazelcast.config.FileSystemXmlConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.cache.Cache;
import org.cache.CacheFactory;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.Channel;
import org.jgroups.View;

/**
 * @author Bela Ban
 * @since x.y
 */
@SuppressWarnings("unused")
@Listener
public class HazelcastCacheFactory<K,V> implements CacheFactory<K,V> {
    protected HazelcastInstance hc;

    /** Empty constructor needed for an instance to be created via reflection */
    public HazelcastCacheFactory() {
    }

    public void init(String config) throws Exception {
        com.hazelcast.config.Config conf=new FileSystemXmlConfig(config);
        hc=Hazelcast.newHazelcastInstance(conf);
    }

    public void destroy() {
        hc.shutdown();
    }

    public Cache<K,V> create(String cache_name) {
        return new HazelcastCache<>(hc.getMap(cache_name));
    }

    @ViewChanged
    public static void viewChanged(ViewChangedEvent evt) {
        Transport transport=evt.getCacheManager().getTransport();
        if(transport instanceof JGroupsTransport) {
            Channel ch=((JGroupsTransport)transport).getChannel();
            View view=ch.getView();
            System.out.println("** view: " + view);
        }
        else
            System.out.println("** view: " + evt);
    }
}
