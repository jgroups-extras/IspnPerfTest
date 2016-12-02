package org.perf;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.cache.impl.DistCacheFactory;
import org.cache.impl.HazelcastCacheFactory;
import org.cache.impl.InfinispanCacheFactory;
import org.cache.impl.tri.TriCacheFactory;
import org.jgroups.*;
import org.jgroups.annotations.Property;
import org.jgroups.blocks.MethodCall;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.ResponseMode;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.util.*;
import org.jgroups.util.UUID;

import javax.management.MBeanServer;
import java.io.DataInput;
import java.io.DataOutput;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;


/**
 * Mimics distributed mode by invoking N (default:50000) requests, 20% writes and 80% reads on random keys in range
 * [1 .. N]. Every member in the cluster does this and the initiator waits until everyone is done, tallies the results
 * (sent to it by every member) and prints stats (throughput).
 * @author Bela Ban
 */
public class Test extends ReceiverAdapter {
    protected CacheFactory<Integer,byte[]> cache_factory;
    protected Cache<Integer,byte[]>        cache;
    protected JChannel                     control_channel;
    protected Address                      local_addr;
    protected RpcDispatcher                disp;
    protected final List<Address>          members=new ArrayList<>();
    protected volatile View                view;
    protected final LongAdder              num_requests=new LongAdder();
    protected final LongAdder              num_reads=new LongAdder();
    protected final LongAdder              num_writes=new LongAdder();
    protected volatile boolean             looping=true;
    protected Thread                       event_loop_thread;
    protected Integer[]                    keys;


    // ============ configurable properties ==================
    @Property
    protected int     num_threads=100;
    @Property
    protected int     num_keys=100000, time_secs=60, msg_size=1000;
    @Property
    protected double  read_percentage=1.0; // 80% reads, 20% writes // todo: change 1.0 back to 0.8
    @Property
    protected boolean print_details=true;
    @Property
    protected boolean print_invokers;
    // ... add your own here, just don't forget to annotate them with @Property
    // =======================================================

    protected static final Method[] METHODS=new Method[16];
    protected static final short    START_ISPN   = 1;
    protected static final short    GET_CONFIG   = 2;
    protected static final short    SET          = 3;
    protected static final short    GET_CONTENTS = 4;
    protected static final short    QUIT_ALL     = 5;

    // 3 longs at the start of each buffer for validation
    protected static final int      VALIDATION_SIZE=Global.LONG_SIZE *3;

    protected static final String infinispan_factory=InfinispanCacheFactory.class.getName();
    protected static final String hazelcast_factory=HazelcastCacheFactory.class.getName();
    protected static final String coherence_factory="org.cache.impl.coh.CoherenceCacheFactory"; // to prevent loading of Coherence up-front
    protected static final String dist_factory=DistCacheFactory.class.getName();
    protected static final String tri_factory=TriCacheFactory.class.getName();

    protected static final String input_str="[1] Start test [2] View [3] Cache size [4] Threads (%d) " +
      "\n[5] Keys (%d) [6] Time (secs) (%d) [7] Value size (%s) [8] Validate" +
      "\n[p] Populate cache [c] Clear cache [v] Versions" +
      "\n[r] Read percentage (%.2f) " +
      "\n[d] Details (%b)  [i] Invokers (%b) [l] dump local cache" +
      "\n[q] Quit [X] Quit all\n";

    static {
        try {
            METHODS[START_ISPN]=Test.class.getMethod("startIspnTest");
            METHODS[GET_CONFIG]=Test.class.getMethod("getConfig");
            METHODS[SET]=Test.class.getMethod("set", String.class, Object.class);
            METHODS[GET_CONTENTS]=Test.class.getMethod("getContents");
            METHODS[QUIT_ALL]=Test.class.getMethod("quitAll");
            ClassConfigurator.add((short)11000, Results.class);
        }
        catch(NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }


    public void init(String factory_name, String cfg, String jgroups_config, String cache_name) throws Exception {
        Class<CacheFactory> clazz=Util.loadClass(factory_name, (Class)null);
        cache_factory=clazz.newInstance();
        cache_factory.init(cfg);
        cache=cache_factory.create(cache_name);

        control_channel=new JChannel(jgroups_config);
        disp=new RpcDispatcher(control_channel, this).setMembershipListener(this);
        disp.setMethodLookup(id -> METHODS[id]);
        control_channel.connect("cfg");
        local_addr=control_channel.getAddress();

        try {
            MBeanServer server=Util.getMBeanServer();
            JmxConfigurator.registerChannel(control_channel, server, "control-channel", control_channel.getClusterName(), true);
        }
        catch(Throwable ex) {
            System.err.println("registering the channel in JMX failed: " + ex);
        }

        if(members.size() >= 2) {
            Address coord=members.get(0);
            Config config=disp.callRemoteMethod(coord, new MethodCall(GET_CONFIG), new RequestOptions(ResponseMode.GET_ALL, 5000));
            if(config != null) {
                applyConfig(config);
                System.out.println("Fetched config from " + coord + ": " + config);
            }
            else
                System.err.println("failed to fetch config from " + coord);
        }

        if(!cache.isEmpty()) {
            int size=cache.size();
            if(size < 10)
                System.out.println("cache already contains elements: " + cache.keySet());
            else
                System.out.println("cache already contains " + size + " elements");
        }
        keys=createKeys(num_keys);
        System.out.printf("created %d keys: [%d-%d]\n", keys.length, keys[0], keys[keys.length - 1]);
    }

    void stop() {
        Util.close(control_channel);
        cache_factory.destroy();
    }

    protected void startEventThread() {
        event_loop_thread=new Thread("EventLoop") {
            public void run() {
                try {
                    eventLoop();
                }
                catch(Throwable ex) {
                    ex.printStackTrace();
                    Test.this.stop();
                }
            }
        };
        event_loop_thread.setDaemon(true);
        event_loop_thread.start();
    }

    protected void stopEventThread() {
        Thread tmp=event_loop_thread;
        looping=false;
        if(tmp != null)
            tmp.interrupt();
        stop();
    }

    protected static Integer[] createKeys(int num_keys) {
        Integer[] retval=new Integer[num_keys];
        for(int i=0; i < num_keys; i++)
            retval[i]=i + 1;
        return retval;
    }

    public void viewAccepted(View new_view) {
        this.view=new_view;
        members.clear();
        members.addAll(new_view.getMembers());
    }


    // =================================== callbacks ======================================


    public Results startIspnTest() throws Throwable {
        num_requests.reset();
        num_reads.reset();
        num_writes.reset();

        try {
            System.out.printf("Running test for %d seconds:\n", time_secs);

            // The first call needs to be synchronous with OOB !
            final CountDownLatch latch=new CountDownLatch(1);
            CacheInvoker[] invokers=new CacheInvoker[num_threads];
            for(int i=0; i < invokers.length; i++) {
                invokers[i]=new CacheInvoker(latch);
                invokers[i].setName("invoker-" + i);
                invokers[i].start();
            }

            double tmp_interval=(time_secs * 1000.0) / 10.0;
            long interval=(long)tmp_interval;

            long start=System.currentTimeMillis();
            latch.countDown(); // starts all sender threads
            for(int i=1; i <= 10; i++) {
                Util.sleep(interval);
                if(print_details)
                    System.out.printf("%d: %s\n", i, printAverage(start));
                else
                    System.out.printf(".");
            }

            Arrays.stream(invokers).forEach(CacheInvoker::cancel);
            for(CacheInvoker invoker : invokers)
                invoker.join();

            long time=System.currentTimeMillis() - start;
            System.out.println("\ndone (in " + time + " ms)\n");

            AverageMinMax get_avg=null, put_avg=null;
            if(print_invokers)
                System.out.printf("Round trip times (min/avg/max us):\n");
            for(CacheInvoker inv : invokers) {
                if(print_invokers)
                    System.out.printf("%s: get %.2f / %.2f / %.2f, put: %.2f / %.2f / %.2f\n", inv.getId(),
                                      inv.get_avg.min() / 1000.0, inv.get_avg.average() / 1000.0, inv.get_avg.max() / 1000.0,
                                      inv.put_avg.min() / 1000.0, inv.put_avg.average() / 1000.0, inv.put_avg.max() / 1000.0);
                if(get_avg == null)
                    get_avg=inv.get_avg;
                else
                    get_avg.merge(inv.get_avg);
                if(put_avg == null)
                    put_avg=inv.put_avg;
                else
                    put_avg.merge(inv.put_avg);
            }
            if(print_details || print_invokers)
                System.out.printf("\nall: get %.2f / %.2f / %.2f, put: %.2f / %.2f / %.2f\n",
                                  get_avg.min() / 1000.0, get_avg.average() / 1000.0, get_avg.max() / 1000.0,
                                  put_avg.min() / 1000.0, put_avg.average() / 1000.0, put_avg.max() / 1000.0);
            return new Results(num_reads.sum(), num_writes.sum(), time, get_avg, put_avg);
        }
        catch(Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    public void quitAll() {
        System.out.println("-- received quitAll(): shutting down");
        stopEventThread();
    }


    public void set(String field_name, Object value) {
        Field field=Util.getField(this.getClass(), field_name);
        if(field == null)
            System.err.println("Field " + field_name + " not found");
        else {
            Util.setField(field, this, value);
            System.out.println(field.getName() + "=" + value);
        }
        changeKeySet();
    }


    public Config getConfig() {
        Config config=new Config();
        for(Field field : Util.getAllDeclaredFields(Test.class)) {
            if(field.isAnnotationPresent(Property.class)) {
                config.add(field.getName(), Util.getField(field, this));
            }
        }
        return config;
    }

    protected void applyConfig(Config config) {
        for(Map.Entry<String,Object> entry : config.values.entrySet()) {
            Field field=Util.getField(getClass(), entry.getKey());
            Util.setField(field, this, entry.getValue());
        }
        changeKeySet();
    }

    public Map<Integer,byte[]> getContents() {
        Map<Integer,byte[]> contents=cache.getContents();
        Map<Integer,byte[]> retval=new HashMap<>(contents.size()); // we need a copy, cannot modify the cache directly!
        for(Map.Entry<Integer,byte[]> entry: contents.entrySet()) {
            byte[] val=entry.getValue();
            // we only need the first 3 longs:
            byte[] tmp_val=Arrays.copyOf(val, VALIDATION_SIZE);
            retval.put(entry.getKey(), tmp_val);
        }
        return retval;
    }

    // ================================= end of callbacks =====================================


    public void eventLoop() throws Throwable {
        while(looping) {
            int c=Util.keyPress(String.format(input_str,
                                              num_threads, keys != null? keys.length : 0, time_secs, Util.printBytes(msg_size),
                                              read_percentage, print_details, print_invokers));
            switch(c) {
                case -1:
                    break;
                case '1':
                    startBenchmark(new MethodCall(START_ISPN));
                    break;
                case '2':
                    printView();
                    break;
                case '3':
                    printCacheSize();
                    break;
                case '4':
                    changeFieldAcrossCluster("num_threads", Util.readIntFromStdin("Number of sender threads: "));
                    break;
                case '5':
                    changeFieldAcrossCluster("num_keys", Util.readIntFromStdin("Number of keys: "));
                    break;
                case '6':
                    changeFieldAcrossCluster("time_secs", Util.readIntFromStdin("Time (secs): "));
                    break;
                case '7':
                    int new_msg_size=Util.readIntFromStdin("Message size: ");
                    if(new_msg_size < Global.LONG_SIZE*3)
                        System.err.println("msg size must be >= " + Global.LONG_SIZE*3);
                    else
                        changeFieldAcrossCluster("msg_size", new_msg_size);
                    break;
                case '8':
                    validate();
                    break;
                case 'c':
                    clearCache();
                    break;
                case 'd':
                    changeFieldAcrossCluster("print_details", !print_details);
                    break;
                case 'i':
                    changeFieldAcrossCluster("print_invokers", !print_invokers);
                    break;
                case 'r':
                    double percentage=getReadPercentage();
                    if(percentage >= 0)
                        changeFieldAcrossCluster("read_percentage", percentage);
                    break;
                case 'p':
                    populateCache();
                    break;
                case 'v':
                    System.out.printf("JGroups: %s, Infinispan: %s\n",
                                      org.jgroups.Version.printDescription(), org.infinispan.Version.printVersion());
                    break;
                case 'q':
                    stop();
                    return;
                case 'l':
                    dumpLocalCache();
                    break;
                case 'X':
                    try {
                        RequestOptions options=new RequestOptions(ResponseMode.GET_NONE, 500)
                          .setFlags(Message.Flag.OOB, Message.Flag.DONT_BUNDLE, Message.Flag.NO_FC);
                        disp.callRemoteMethods(null, new MethodCall(QUIT_ALL), options);
                    }
                    catch(Throwable t) {
                        System.err.println("Calling quitAll() failed: " + t);
                    }
                    break;
                case '\n':
                case '\r':
                    break;
                default:
                    break;
            }
        }
    }


    /** Kicks off the benchmark on all cluster nodes */
    protected void startBenchmark(MethodCall call) {
        RspList<Results> responses=null;
        try {
            RequestOptions opts=new RequestOptions(ResponseMode.GET_ALL, 0)
              .setFlags(Message.Flag.OOB, Message.Flag.DONT_BUNDLE, Message.Flag.NO_FC);
            responses=disp.callRemoteMethods(null, call, opts);
        }
        catch(Throwable t) {
            System.err.println("starting the benchmark failed: " + t);
            return;
        }

        long total_reqs=0, total_time=0, longest_time=0;
        AverageMinMax get_avg=null, put_avg=null;
        System.out.println("\n======================= Results: ===========================");
        for(Map.Entry<Address,Rsp<Results>> entry : responses.entrySet()) {
            Address mbr=entry.getKey();
            Rsp<Results> rsp=entry.getValue();
            if(rsp.hasException()) {
                Throwable t=rsp.getException();
                System.out.printf("%s: %s (cause=%s)\n", mbr, t, t.getCause());
                continue;
            }

            Results result=rsp.getValue();
            if(result != null) {
                total_reqs+=result.num_gets + result.num_puts;
                total_time+=result.time;
                longest_time=Math.max(longest_time, result.time);
                if(get_avg == null)
                    get_avg=result.get_avg;
                else
                    get_avg.merge(result.get_avg);
                if(put_avg == null)
                    put_avg=result.put_avg;
                else
                    put_avg.merge(result.put_avg);
            }
            System.out.println(mbr + ": " + result);
        }
        double reqs_sec_node=total_reqs / (total_time / 1000.0);
        double reqs_sec_cluster=total_reqs / (longest_time / 1000.0);
        double throughput=reqs_sec_node * msg_size;
        System.out.println("\n");
        System.out.println(Util.bold(String.format("Throughput: %.2f reqs/sec/node (%s/sec) %.2f reqs/sec/cluster\n" +
                                                     "Roundtrip:  gets %s, puts %s\n",
                                                   reqs_sec_node, Util.printBytes(throughput), reqs_sec_cluster,
                                                   print(get_avg, print_details), print(put_avg, print_details))));
        System.out.println("\n\n");
    }


    static double getReadPercentage() throws Exception {
        double tmp=Util.readDoubleFromStdin("Read percentage: ");
        if(tmp < 0 || tmp > 1.0) {
            System.err.println("read percentage must be >= 0 or <= 1.0");
            return -1;
        }
        return tmp;
    }

    int getAnycastCount() throws Exception {
        int tmp=Util.readIntFromStdin("Anycast count: ");
        View tmp_view=control_channel.getView();
        if(tmp > tmp_view.size()) {
            System.err.println("anycast count must be smaller or equal to the view size (" + tmp_view + ")\n");
            return -1;
        }
        return tmp;
    }


    protected void changeFieldAcrossCluster(String field_name, Object value) throws Exception {
        disp.callRemoteMethods(null, new MethodCall(SET, field_name, value), RequestOptions.SYNC());
    }


    void printView() {
        System.out.printf("\n-- local: %s\n-- view: %s\n", local_addr, view);
        try {
            System.in.skip(System.in.available());
        }
        catch(Exception e) {
        }
    }

    protected static String print(AverageMinMax avg, boolean details) {
        return details? String.format("min/avg/max = %.2f/%.2f/%.2f us",
                                      avg.min() / 1000.0, avg.average() / 1000.0, avg.max() / 1000.0) :
          String.format("avg = %.2f us", avg.average() / 1000.0);
    }

    protected String printAverage(long start_time) {
        long time=System.currentTimeMillis() - start_time;
        long reads=num_reads.sum(), writes=num_writes.sum();
        double reqs_sec=num_requests.sum() / (time / 1000.0);
        return String.format("%.2f reqs/sec (%d reads %d writes)", reqs_sec, reads, writes);
    }

    protected void printCacheSize() {
        int size=cache.size();
        System.out.println("-- cache has " + size + " elements");
    }


    protected void clearCache() {
        cache.clear();
    }

    protected void changeKeySet() {
        if(keys == null || keys.length != num_keys) {
            int old_key_size=keys != null? keys.length : 0;
            keys=createKeys(num_keys);
            System.out.printf("created %d keys: [%d-%d], old key set size: %d\n",
                              keys.length, keys[0], keys[keys.length - 1], old_key_size);
        }
    }

    // Inserts num_keys keys into the cache (in parallel)
    protected void populateCache() throws InterruptedException {
        final AtomicInteger key=new AtomicInteger(1);
        final int           print=num_keys / 10;
        final UUID          local_uuid=(UUID)local_addr;
        final AtomicInteger count=new AtomicInteger(1);

        Thread[] inserters=new Thread[50];
        for(int i=0; i < inserters.length; i++) {
            inserters[i]=new Thread(() -> {
                for(;;) {
                    byte[] buffer=new byte[msg_size];
                    int k=key.getAndIncrement();
                    if(k > num_keys)
                        return;
                    try {
                        writeTo(local_uuid, count.getAndIncrement(), buffer, 0);
                        cache.put(k, buffer);
                        num_writes.increment();
                        if(print > 0 && k > 0 && k % print == 0)
                            System.out.print(".");
                    }
                    catch(Throwable t) {
                        t.printStackTrace();
                    }
                }
            });
        }
        for(Thread inserter: inserters)
            inserter.start();
        for(Thread inserter: inserters)
            inserter.join();
    }

    protected static void writeTo(UUID addr, long seqno, byte[] buf, int offset) {
        long low=addr.getLeastSignificantBits(), high=addr.getMostSignificantBits();
        Bits.writeLong(low, buf, offset);
        Bits.writeLong(high, buf, offset + Global.LONG_SIZE);
        Bits.writeLong(seqno, buf, offset + Global.LONG_SIZE *2);
    }


    protected void validate() {
        View v=control_channel.getView();
        Map<Integer, List<byte[]>> map=new HashMap<>(num_keys), error_map=new HashMap<>();

        int total_keys=0, tot_errors=0;
        for(Address mbr : v) {
            Map<Integer,byte[]> mbr_map;
            if(Objects.equals(mbr, local_addr))
                mbr_map=getContents();
            else {
                try {
                    mbr_map=disp.callRemoteMethod(mbr, new MethodCall(GET_CONTENTS), RequestOptions.SYNC().timeout(60000));
                }
                catch(Throwable t) {
                    System.err.printf("failed fetching contents from %s: %s\n", mbr, t);
                    return; // return from validation
                }
            }

            int size=mbr_map.size();
            int errors=0;
            total_keys+=size;

            System.out.printf("-- Validating contents of %s (%d keys): ", mbr, size);
            for(Map.Entry<Integer,byte[]> entry: mbr_map.entrySet()) {
                Integer key=entry.getKey();
                byte[] val=entry.getValue();

                List<byte[]> values=map.get(key);
                if(values == null) { // key has not yet been added
                    map.put(key, values=new ArrayList<>());
                    values.add(val);
                }
                else {
                    if(values.size() >= 2)
                        System.err.printf("key %d already has 2 values\n", key);
                    else {
                        values.add(val);
                    }
                    byte[] val1=values.get(0);
                    for(byte[] value: values) {
                        if(!Arrays.equals(value, val1)) {
                            errors++;
                            tot_errors++;
                            error_map.put(key, values);
                            break;
                        }
                    }
                }
            }
            if(errors > 0)
                System.err.printf("FAIL: %d errors\n", errors);
            else
                System.out.printf("OK\n");
        }
        System.out.println(Util.bold(String.format("\nValidated %d keys total, %d errors\n\n", total_keys, tot_errors)));
        if(tot_errors > 0) {
            for(Map.Entry<Integer,List<byte[]>> entry: error_map.entrySet()) {
                Integer key=entry.getKey();
                List<byte[]> values=entry.getValue();
                System.err.printf("%d:\n%s\n", key, print(values));
            }
        }
    }

    protected static String print(List<byte[]> list) {
        StringBuilder sb=new StringBuilder();
        for(byte[] b: list) {
            UUID uuid=new UUID(Bits.readLong(b, 0), Bits.readLong(b, Global.LONG_SIZE));
            long num=Bits.readLong(b, Global.LONG_SIZE*2);
            sb.append("  ").append(uuid).append(": ").append(num).append("\n");
        }
        return sb.toString();
    }

    protected void dumpLocalCache() {
        Map<Integer,byte[]> local_cache=new TreeMap<>(getContents());
        for(Map.Entry<Integer, byte[]> entry: local_cache.entrySet()) {
            System.out.printf("%d: %d\n", entry.getKey(), Bits.readLong(entry.getValue(), Global.LONG_SIZE*2));
        }
        System.out.printf("\n%d local values found\n", local_cache.size());
    }

    protected class CacheInvoker extends Thread {
        protected final CountDownLatch latch;
        protected final AverageMinMax  get_avg=new AverageMinMax(); // in ns
        protected final AverageMinMax  put_avg=new AverageMinMax(); // in ns
        protected volatile boolean     running=true;
        protected UUID                 local_uuid=(UUID)local_addr;
        protected long                 count=0;


        public CacheInvoker(CountDownLatch latch) {
            this.latch=latch;
        }

        public void cancel() {running=false;}

        public void run() {
            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
            }
            while(running) {
                num_requests.increment();

                // get a random key in range [0 .. num_keys-1]
                int index=(int)Util.random(num_keys) -1;
                Integer key=keys[index];
                boolean is_this_a_read=Util.tossWeightedCoin(read_percentage);

                // try the operation until it is successful
                while(true) {
                    try {
                        if(is_this_a_read) {
                            long start=System.nanoTime();
                            cache.get(key);
                            long time=System.nanoTime() - start;
                            get_avg.add(time);
                            num_reads.increment();
                        }
                        else {
                            byte[] buffer=new byte[msg_size];
                            writeTo(local_uuid, count++, buffer, 0);
                            long start=System.nanoTime();
                            cache.put(key, buffer);
                            long time=System.nanoTime() - start;
                            put_avg.add(time);
                            num_writes.increment();
                        }
                        break;
                    }
                    catch(Throwable t) {
                        t.printStackTrace();
                    }
                }
            }
        }
    }


    public static class Results implements Streamable {
        long num_gets, num_puts, time; // ms
        AverageMinMax get_avg, put_avg;

        public Results() {}

        public Results(long num_gets, long num_puts, long time, AverageMinMax get_avg, AverageMinMax put_avg) {
            this.num_gets=num_gets;
            this.num_puts=num_puts;
            this.time=time;
            this.get_avg=get_avg;
            this.put_avg=put_avg;
        }

        public void writeTo(DataOutput out) throws Exception {
            out.writeLong(num_gets);
            out.writeLong(num_puts);
            out.writeLong(time);
            Util.writeStreamable(get_avg, out);
            Util.writeStreamable(put_avg, out);
        }

        public void readFrom(DataInput in) throws Exception {
            num_gets=in.readLong();
            num_puts=in.readLong();
            time=in.readLong();
            get_avg=Util.readStreamable(AverageMinMax.class, in);
            put_avg=Util.readStreamable(AverageMinMax.class, in);
        }

        public String toString() {
            long total_reqs=num_gets + num_puts;
            double total_reqs_per_sec=total_reqs / (time / 1000.0);
            return String.format("%.2f reqs/sec (%d GETs, %d PUTs), avg RTT (us) = %.2f get, %.2f put",
                                 total_reqs_per_sec, num_gets, num_puts, get_avg.average()/1000.0, put_avg.average()/1000.0);
        }
    }


    public static class Config implements Streamable {
        protected final Map<String,Object> values=new HashMap<>();

        public Config() {}

        public Config add(String key, Object value) {
            values.put(key, value);
            return this;
        }

        public void writeTo(DataOutput out) throws Exception {
            out.writeInt(values.size());
            for(Map.Entry<String,Object> entry: values.entrySet()) {
                Bits.writeString(entry.getKey(), out);
                Util.objectToStream(entry.getValue(), out);
            }
        }

        public void readFrom(DataInput in) throws Exception {
            int size=in.readInt();
            for(int i=0; i < size; i++) {
                String key=Bits.readString(in);
                Object value=Util.objectFromStream(in);
                if(key == null)
                    continue;
                values.put(key, value);
            }
        }

        public String toString() {
            return values.toString();
        }
    }





    public static void main(String[] args) {
        String           config_file="dist-sync.xml";
        String           cache_name="perf-cache";
        String           cache_factory_name=InfinispanCacheFactory.class.getName();
        String           jgroups_config="control.xml";
        boolean          run_event_loop=true;

        for(int i=0; i < args.length; i++) {
            if(args[i].equals("-cfg")) {
                config_file=args[++i];
                continue;
            }
            if(args[i].equals("-cache")) {
                cache_name=args[++i];
                continue;
            }
            if("-factory".equals(args[i])) {
                cache_factory_name=args[++i];
                continue;
            }
            if("-nohup".equals(args[i])) {
                run_event_loop=false;
                continue;
            }
            if("-jgroups-cfg".equals(args[i])) {
                jgroups_config=args[++i];
                continue;
            }
            help();
            return;
        }

        Test test=null;
        try {
            test=new Test();
            switch(cache_factory_name) {
                case "ispn":
                    cache_factory_name=infinispan_factory;
                    break;
                case "hc":
                    cache_factory_name=hazelcast_factory;
                    break;
                case "coh":
                    cache_factory_name=coherence_factory;
                    break;
                case "dist":
                    cache_factory_name=dist_factory;
                    break;
                case "tri":
                    cache_factory_name=tri_factory;
                    break;
            }
            test.init(cache_factory_name, config_file, jgroups_config, cache_name);
            if(run_event_loop)
                test.startEventThread();
        }
        catch(Throwable ex) {
            ex.printStackTrace();
            if(test != null)
                test.stop();
        }
    }

    static void help() {
        System.out.printf("Test [-factory <cache factory classname>] [-cfg <config-file>] " +
                            "[-cache <cache-name>] [-jgroups-cfg] [-nohup]\n" +
                            "Valid factory names:" +
                            "\n  ispn: %s\n  hc:   %s\n  coh:  %s\n  dist: %s\n  tri:  %s\n\n",
                          infinispan_factory, hazelcast_factory, coherence_factory, dist_factory, tri_factory);
    }


}