package org.cache.impl;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Properties;

/**
 * CacheFactory which uses a remote Infinispan server via Hotrod
 * @author Bela Ban
 * @since x.y
 */
@SuppressWarnings("unused")
// @Listener
public class HotrodCacheFactory<K,V> implements CacheFactory<K,V> {
    protected RemoteCacheManager  remoteCacheManager;
    protected static final String HR_PROPRS="hotrod-factory.properties";
    protected static final String trustStoreFile="trustStoreFile";
    protected static final String trustStorePassword="trustStorePassword";
    protected static final String keystoreFile="keystoreFile";
    protected static final String keystorePassword="keystorePassword";

    /** Empty constructor needed for an instance to be created via reflection */
    public HotrodCacheFactory() {
    }

    /**
     * Initializes the remote cache manager
     * @param config The properties file for setting up the Hotrod client (default: hotrod-client.properties)
     * @throws Exception
     */
    public void init(String config) throws Exception {
        Properties props=new Properties();
        ClassLoader cl=getClass().getClassLoader();
        if(config == null)
            config=HR_PROPRS;

        InputStream in=null;
        try {
            in=cl.getResourceAsStream(config);
            if(in == null)
                throw new FileNotFoundException(config);
            props.load(in);
            org.infinispan.client.hotrod.configuration.ConfigurationBuilder cb=new ConfigurationBuilder()
              .withProperties(props);
            remoteCacheManager=new RemoteCacheManager(cb.build());
        }
        finally {
            if(in != null)
                in.close();
        }
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
