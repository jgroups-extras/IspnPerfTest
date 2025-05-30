
package org.jmh;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.cache.impl.InfinispanCacheFactory;
import org.cache.impl.LocalCacheFactory;
import org.cache.impl.RaftCacheFactory;
import org.cache.impl.tri.TriCacheFactory;
import org.jgroups.util.Util;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@Measurement(timeUnit=TimeUnit.SECONDS,iterations=60)
@Threads(1)
public class Benchmark {
    protected CacheFactory<Integer,byte[]> cache_factory;
    protected Cache<Integer,byte[]>        cache;

    protected byte[]                       BUFFER;
    protected long                         num_reads, num_writes; // only works with single threads, or synchronization

    // <cache type>:<config>
    // @Param("tri:jgroups-tcp.xml")
    @Param("ispn:dist-sync.xml")
    protected String                       config;

    @Param("perf-cache")
    protected String                       cache_name;

    // this value can be changed, e.g. by passing -p "read_percentage=0.8,1.0" to the test runner
    @Param("1.0")
    protected double                       read_percentage;

    @Param("1000")
    protected int                          msg_size;

    @Param("100000")
    protected int                          num_keys; // [1 .. num_keys]

    protected static final String          ispn_factory=InfinispanCacheFactory.class.getName();
    protected static final String          tri_factory=TriCacheFactory.class.getName();
    protected static final String          raft_factory=RaftCacheFactory.class.getName();
    protected static final String          local_factory=LocalCacheFactory.class.getName();


    public Benchmark config(String config) {
        this.config=config;
        return this;
    }

    public Benchmark msgSize(int msg_size) {
        this.msg_size=msg_size;
        return this;
    }

    public Benchmark numKeys(int num_keys) {
        this.num_keys=num_keys;
        return this;
    }

    public Benchmark readPercentage(double read_percentage) {
        this.read_percentage=read_percentage;
        return this;
    }

    @Setup
    public void setup() throws Exception {
        BUFFER=new byte[msg_size];
        String[] tmp=split(config);
        String cache_factory_class=tmp[0];
        String cfg=tmp[1];
        cache_factory=createFactory(cache_factory_class);
        cache_factory.init(cfg);
        cache=cache_factory.create(cache_name, null);
        System.out.printf("\n-- created cache from factory %s\n", cache_factory.getClass().getSimpleName());
        if(cache.isEmpty()) {
            System.out.printf("-- adding keys [1 .. %,d]: ", num_keys);
            for(int i=1; i <= num_keys; i++)
                cache.put(i, BUFFER);
            System.out.println("OK\n");
        }
        else
            System.out.printf("-- cache is already populated: %,d keys\n", cache.size());
    }

    @TearDown
    public void destroy() {
        System.out.printf("-- num_reads: %,d, num_writes: %,d\n", num_reads, num_writes);
        cache_factory.destroy();
    }

    @org.openjdk.jmh.annotations.Benchmark
    @BenchmarkMode({Mode.Throughput}) @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(1)
    @Warmup(time=10,timeUnit=TimeUnit.SECONDS)
    public void testMethod() throws Exception {
        // get a random key in range [1 .. num_keys]
        int key=Util.random(num_keys) -1;
        boolean is_this_a_read=Util.tossWeightedCoin(read_percentage);
        if(is_this_a_read) {
            cache.get(key);
            num_reads++;
        }
        else {
            cache.put(key, BUFFER);
            num_writes++;
        }
    }

    protected static String[] split(String c) {
        int index=c.indexOf(":");
        if(index < 0)
            throw new IllegalArgumentException(String.format("failed to parse cache type and config from %s", c));
        String cache_type=c.substring(0, index), cfg=c.substring(index+1);
        return new String[]{cache_type.trim(), cfg.trim()};
    }

    protected static CacheFactory<Integer,byte[]> createFactory(String cache_factory_name) throws Exception {
        switch(cache_factory_name) {
            case "ispn":
                return create(ispn_factory);
            case "tri":
                return create(tri_factory);
            case "raft":
                return create(raft_factory);
            case "local":
                return create(local_factory);
            default:
                throw new IllegalArgumentException(String.format("factory %s not known", cache_factory_name));
        }
    }

    protected static CacheFactory<Integer,byte[]> create(String classname) throws Exception {
        ClassLoader loader=Thread.currentThread().getContextClassLoader();
        Class<CacheFactory<Integer,byte[]>> cl=(Class<CacheFactory<Integer,byte[]>>)Util.loadClass(classname, loader);
        return cl.getConstructor().newInstance();
    }

    public static void main(String[] args) throws Exception {
        Benchmark b=new Benchmark();
        b.setup();
        System.out.println("-- started as server");
        Util.keyPress("enter to terminate");

        b.destroy();
    }


}
