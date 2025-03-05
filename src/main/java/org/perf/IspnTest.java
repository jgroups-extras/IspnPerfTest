package org.perf;

import org.cache.impl.InfinispanCacheFactory;
import org.cache.Cache;
import org.cache.CacheFactory;
import org.jgroups.util.Util;

/**
 * @author Bela Ban
 * @since x.y
 */
public class IspnTest {
    protected CacheFactory<Integer,byte[]> cache_factory;
    protected Cache<Integer,byte[]> cache;
    protected static final String          config="dist-sync.xml";
    protected static final int             NUM=1000;

    protected void start() throws Exception {
        cache_factory=new InfinispanCacheFactory();
        cache_factory.init(config);
        cache=cache_factory.create("perf-cache", "X");

        for(int i=0; i < NUM; i++)
            cache.put(i, new byte[i]);

        boolean looping=true;
        while(looping) {
            int c=Util.keyPress("[1] read");
            switch(c) {
                case '1':

                    int key=(int)Util.random(100);

                    byte[] val=cache.get(key);
                    System.out.printf("%d -> got %d bytes\n", key, val != null? val.length : -1);
                    break;
                case 'x':
                    looping=false;
                    break;
            }
        }
        cache_factory.destroy();
    }


    public static void main(String[] args) throws Exception {
        new IspnTest().start();
    }
}
