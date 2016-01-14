package org.perf;

import org.cache.CacheFactory;
import org.cache.impl.CoherenceCacheFactory;
import org.cache.impl.HazelcastCacheFactory;
import org.cache.impl.InfinispanCacheFactory;
import org.jgroups.*;
import org.jgroups.annotations.Property;
import org.jgroups.blocks.MethodCall;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.ResponseMode;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.util.*;

import javax.management.MBeanServer;
import java.io.DataInput;
import java.io.DataOutput;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Mimics distributed mode by invoking N (default:20000) requests, 20% writes and 80% reads on random keys in range
 * [1 .. N]. Every member inthe cluster does this and the initiator waits until everyone is done, tallies the results
 * (sent to it by every member) and prints stats (throughput).
 * @author Bela Ban
 */
public class Test extends ReceiverAdapter {
    protected CacheFactory<Integer,byte[]> cache_factory;

    protected org.cache.Cache<Integer,byte[]>  cache;
    protected JChannel               channel;
    protected Address                local_addr;
    protected RpcDispatcher          disp;
    protected final List<Address>    members=new ArrayList<>();
    protected volatile View          view;
    protected final AtomicInteger    num_requests=new AtomicInteger(0);
    protected final AtomicInteger    num_reads=new AtomicInteger(0);
    protected final AtomicInteger    num_writes=new AtomicInteger(0);
    protected volatile boolean       looping=true;
    protected Thread                 event_loop_thread;


    // ============ configurable properties ==================
    @Property protected boolean sync=true, oob=true;
    @Property protected int     num_threads=25;
    @Property protected int     num_rpcs=20000, msg_size=1000;
    @Property protected int     anycast_count=2;
    @Property protected boolean msg_bundling=true;
    @Property protected double  read_percentage=0.8; // 80% reads, 20% writes
    @Property protected boolean get_before_put=false; // invoke a sync GET before a PUT
    // ... add your own here, just don't forget to annotate them with @Property
    // =======================================================

    protected static final Method[] METHODS=new Method[16];
    protected static final short    START_UPERF           =  0;
    protected static final short    START_ISPN            =  1;
    protected static final short    GET                   =  2;
    protected static final short    PUT                   =  3;
    protected static final short    GET_CONFIG            =  4;
    protected static final short    SET                   =  5;
    protected static final short    QUIT_ALL              =  6;

    protected final AtomicInteger   COUNTER=new AtomicInteger(1);
    protected byte[]                BUFFER=new byte[msg_size];
    protected static final String   infinispan_factory=InfinispanCacheFactory.class.getName();
    protected static final String   hazelcast_factory=HazelcastCacheFactory.class.getName();
    protected static final String   coherence_factory=CoherenceCacheFactory.class.getName();

    protected static final String input_str="[1] Start UPerf test [2] Start cache test [3] Print view [4] Print cache size" +
      "\n[6] Set sender threads (%d) [7] Set num RPCs (%d) [8] Set payload size (%s) [9] Set anycast count (%d)" +
      "\n[p] Populate cache [c] Clear cache [v] Print versions" +
      "\n[o] Toggle OOB (%b) [s] Toggle sync (%b) [r] Set read percentage (%.2f) [g] get_before_put (%b) " +
      "\n[a] [b] Toggle msg_bundling (%b)" +
      "\n[q] Quit [X] quit all\n";

    static {
        try {
            METHODS[START_UPERF]  = Test.class.getMethod("startUPerfTest");
            METHODS[START_ISPN]   = Test.class.getMethod("startIspnTest");
            METHODS[GET]          = Test.class.getMethod("get", long.class);
            METHODS[PUT]          = Test.class.getMethod("put", long.class, byte[].class);
            METHODS[GET_CONFIG]   = Test.class.getMethod("getConfig");
            METHODS[SET]          = Test.class.getMethod("set", String.class, Object.class);
            METHODS[QUIT_ALL]     = Test.class.getMethod("quitAll");

            ClassConfigurator.add((short)11000, Results.class);
        }
        catch(NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }


    public void init(String factory_name, String cfg, String jgroups_config, String cache_name) throws Exception {
        try {
            Class<CacheFactory> clazz=Util.loadClass(factory_name, (Class)null);
            cache_factory=clazz.newInstance();
            cache_factory.init(cfg);
            cache=cache_factory.create(cache_name);

            channel=new JChannel(jgroups_config);
            disp=new RpcDispatcher(channel, null, this, this);
            disp.setMethodLookup(id -> METHODS[id]);
            channel.connect("config-cluster");
            local_addr=channel.getAddress();

            try {
                MBeanServer server=Util.getMBeanServer();
                JmxConfigurator.registerChannel(channel, server, "control-channel", channel.getClusterName(), true);
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
        }
        catch(Throwable t) {
            t.printStackTrace();
        }
    }

    void stop() {
        Util.close(channel);
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

    public void viewAccepted(View new_view) {
        this.view=new_view;
        members.clear();
        members.addAll(new_view.getMembers());
    }



    // =================================== callbacks ======================================

    public Results startUPerfTest() throws Throwable {
        BUFFER=new byte[msg_size];

        System.out.printf("invoking %d RPCs of %s, sync=%b, oob=%b, msg_bundling=%b",
                          num_rpcs, Util.printBytes(BUFFER.length), sync, oob, msg_bundling);
        int total_gets=0, total_puts=0;
        final AtomicInteger num_rpcs_invoked=new AtomicInteger(0);

        Invoker[] invokers=new Invoker[num_threads];
        for(int i=0; i < invokers.length; i++)
            invokers[i]=new Invoker(members, num_rpcs, num_rpcs_invoked);

        long start=System.currentTimeMillis();
        for(Invoker invoker: invokers)
            invoker.start();

        for(Invoker invoker: invokers) {
            invoker.join();
            total_gets+=invoker.numGets();
            total_puts+=invoker.numPuts();
        }

        long total_time=System.currentTimeMillis() - start;
        System.out.println("\ndone (in " + total_time + " ms)");
        return new Results(total_gets, total_puts, total_time);
    }

    public Results startIspnTest() throws Throwable {
        num_requests.set(0);
        num_reads.set(0);
        num_writes.set(0);

        BUFFER=new byte[msg_size];
        System.out.printf("invoking %d RPCs of %s, sync=%b\n", num_rpcs, Util.printBytes(BUFFER.length), sync);

        // The first call needs to be synchronous with OOB !
        final CountDownLatch latch=new CountDownLatch(1);
        CacheInvoker[] invokers=new CacheInvoker[num_threads];
        for(int i=0; i < invokers.length; i++) {
            invokers[i]=new CacheInvoker(latch);
            invokers[i].setName("invoker-" + i);
            invokers[i].start();
        }

        long start=System.currentTimeMillis();
        latch.countDown();

        for(CacheInvoker invoker: invokers)
            invoker.join();
        long time=System.currentTimeMillis() - start;

        System.out.println("\ndone (in " + time + " ms)");

        return new Results(num_reads.get(), num_writes.get(), time);
    }

    public void quitAll() {
        System.out.println("-- received quitAll(): shutting down");
        stopEventThread();
    }


    public void set(String field_name, Object value) {
        Field field=Util.getField(this.getClass(),field_name);
        if(field == null)
            System.err.println("Field " + field_name + " not found");
        else {
            Util.setField(field, this, value);
            System.out.println(field.getName() + "=" + value);
        }
    }


    @SuppressWarnings("UnusedParameters")
    public byte[] get(long key) {
        return BUFFER;
    }


    @SuppressWarnings("UnusedParameters")
    public void put(long key, byte[] val) {

    }

    public Config getConfig() {
        Config config=new Config();
        for(Field field: Util.getAllDeclaredFields(Test.class)) {
            if(field.isAnnotationPresent(Property.class)) {
                config.add(field.getName(), Util.getField(field, this));
            }
        }
        return config;
    }

    protected void applyConfig(Config config) {
        for(Map.Entry<String,Object> entry: config.values.entrySet()) {
            Field field=Util.getField(getClass(), entry.getKey());
            Util.setField(field, this, entry.getValue());
        }
    }

    // ================================= end of callbacks =====================================


    public void eventLoop() throws Throwable {
        while(looping) {
            int c=Util.keyPress(String.format(input_str,
                                              num_threads, num_rpcs, Util.printBytes(msg_size), anycast_count, oob, sync,
                                              read_percentage, get_before_put, msg_bundling));
            switch(c) {
                case -1:
                    break;
                case '1':
                    startBenchmark(new MethodCall(START_UPERF));
                    break;
                case '2':
                    startBenchmark(new MethodCall(START_ISPN));
                    break;
                case '3':
                    printView();
                    break;
                case '4':
                    printCacheSize();
                    break;
                case '6':
                    changeFieldAcrossCluster("num_threads", Util.readIntFromStdin("Number of sender threads: "));
                    break;
                case '7':
                    changeFieldAcrossCluster("num_rpcs", Util.readIntFromStdin("Number of RPCs: "));
                    break;
                case '8':
                    changeFieldAcrossCluster("msg_size", Util.readIntFromStdin("Message size: "));
                    break;
                case '9':
                    int tmp=getAnycastCount();
                    if(tmp >= 0)
                        changeFieldAcrossCluster("anycast_count", tmp);
                    break;
                case 'c':
                    clearCache();
                    break;
                case 'o':
                    changeFieldAcrossCluster("oob", !oob);
                    break;
                case 's':
                    changeFieldAcrossCluster("sync", !sync);
                    break;
                case 'r':
                    double percentage= getReadPercentage();
                    if(percentage >= 0)
                        changeFieldAcrossCluster("read_percentage", percentage);
                    break;
                case 'b':
                    changeFieldAcrossCluster("msg_bundling", !msg_bundling);
                    break;
                case 'g':
                    changeFieldAcrossCluster("get_before_put", !get_before_put);
                    break;
                case 'p':
                    populateCache();
                    break;
                case 'v':
                    System.out.printf("JGroups: %s\n, Infinispan: %s\n",
                                      org.jgroups.Version.printDescription(), org.infinispan.Version.printVersion());
                    break;
                case 'q':
                    stop();
                    return;
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

        long total_reqs=0, total_time=0;
        System.out.println("\n======================= Results: ===========================");
        for(Map.Entry<Address,Rsp<Results>> entry: responses.entrySet()) {
            Address mbr=entry.getKey();
            Rsp<Results> rsp=entry.getValue();
            Results result=rsp.getValue();
            if(result != null) {
                total_reqs+=result.num_gets + result.num_puts;
                total_time+=result.time;
            }
            System.out.println(mbr + ": " + result);
        }
        double total_reqs_sec=total_reqs / ( total_time/ 1000.0);
        double throughput=total_reqs_sec * msg_size;
        double ms_per_req=total_time / (double)total_reqs;
        System.out.println("\n");
        System.out.println(Util.bold(String.format("Average of %.2f requests / sec (%s / sec), %.2f ms /request",
                                                   total_reqs_sec, Util.printBytes(throughput), ms_per_req)));
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
        View tmp_view=channel.getView();
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
        System.out.println("\n-- view: " + view + '\n');
        try {
            System.in.skip(System.in.available());
        }
        catch(Exception e) {
        }
    }



    protected void printCacheSize() {
        int size=cache.size();
        System.out.println("-- cache has " + size + " elements");
    }


    protected void clearCache() {
        cache.clear();
    }

    // Creates num_rpcs elements
    protected void populateCache() {
        int    print=num_rpcs / 10;
        for(int i=1; i <= num_rpcs; i++) {
            try {
                cache.put(i, BUFFER);
                num_writes.incrementAndGet();
                if(print > 0 && i > 0 && i % print == 0)
                    System.out.print(".");
            }
            catch(Throwable t) {
                t.printStackTrace();
            }
        }
    }


    protected  class Invoker extends Thread {
        protected final List<Address>  dests=new ArrayList<>();
        protected final int            num_rpcs_to_invoke;
        protected final AtomicInteger  num_rpcs_invoked;
        protected int                  num_gets=0;
        protected int                  num_puts=0;
        protected final int            PRINT;


        public Invoker(Collection<Address> dests, int num_rpcs_to_invoke, AtomicInteger num_rpcs_invoked) {
            this.num_rpcs_invoked=num_rpcs_invoked;
            this.dests.addAll(dests);
            this.num_rpcs_to_invoke=num_rpcs_to_invoke;
            PRINT=Math.max(num_rpcs_to_invoke / 10, 10);
            setName("Invoker-" + COUNTER.getAndIncrement());
        }

        
        public int numGets() {return num_gets;}
        public int numPuts() {return num_puts;}


        public void run() {
            Object[] put_args={0, BUFFER};
            Object[] get_args={0};
            MethodCall get_call=new MethodCall(GET, get_args);
            MethodCall put_call=new MethodCall(PUT, put_args);
            RequestOptions get_options=new RequestOptions(ResponseMode.GET_ALL, 40000, false, null);
            RequestOptions put_options=new RequestOptions(sync ? ResponseMode.GET_ALL : ResponseMode.GET_NONE, 40000, true, null);
            RequestOptions get_before_put_options=new RequestOptions(ResponseMode.GET_FIRST, 40000, true, null, Message.Flag.DONT_BUNDLE, Message.Flag.OOB);

            if(oob) {
                get_options.setFlags(Message.Flag.OOB);
                put_options.setFlags(Message.Flag.OOB);
            }
            if(!msg_bundling) {
                get_options.setFlags(Message.Flag.DONT_BUNDLE);
                put_options.setFlags(Message.Flag.DONT_BUNDLE);
            }
            while(true) {
                long i=num_rpcs_invoked.getAndIncrement();
                if(i >= num_rpcs_to_invoke)
                    break;
                if(i > 0 && i % PRINT == 0)
                    System.out.print(".");
                
                boolean get=Util.tossWeightedCoin(read_percentage);

                try {
                    if(get) { // sync GET
                        Address target=pickTarget();
                        if(target != null && target.equals(local_addr)) {
                            // System.out.println("direct invocation on " + local_addr);
                            get(1); // invoke the call directly if local
                        }
                        else {
                            get_args[0]=i;
                            disp.callRemoteMethod(target, get_call, get_options);
                        }
                        num_gets++;
                    }
                    else {    // sync or async (based on value of 'sync') PUT
                        final Collection<Address> targets=pickAnycastTargets();
                        if(get_before_put) {
                            // sync GET
                            get_args[0]=i;
                            disp.callRemoteMethods(targets, get_call, get_before_put_options);
                            num_gets++;
                        }
                        put_args[0]=i;
                        disp.callRemoteMethods(targets, put_call, put_options);
                        num_puts++;
                    }
                }
                catch(Throwable throwable) {
                    throwable.printStackTrace();
                }
            }
        }

        protected Address pickTarget() {
            return Util.pickRandomElement(dests);
            /*int index=dests.indexOf(local_addr);
            int new_index=(index +1) % dests.size();
            return dests.get(new_index);*/
        }

        protected Collection<Address> pickAnycastTargets() {
            Collection<Address> anycast_targets=new ArrayList<>(anycast_count);
            int index=dests.indexOf(local_addr);
            for(int i=index + 1; i < index + 1 + anycast_count; i++) {
                int new_index=i % dests.size();
                Address tmp=dests.get(new_index);
                if(!anycast_targets.contains(tmp))
                    anycast_targets.add(tmp);
            }
            return anycast_targets;
        }
    }



    protected class CacheInvoker extends Thread {
        protected final CountDownLatch latch;
        protected final int            print=num_rpcs / 10;

        public CacheInvoker(CountDownLatch latch) {
            this.latch=latch;
        }

        public void run() {
            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
                return;
            }

            for(;;) {
                int i=num_requests.incrementAndGet();
                if(i > num_rpcs) {
                    num_requests.decrementAndGet();
                    return;
                }

                // get a random key in range [0 .. num_rpcs-1]
                int key=(int)Util.random(num_rpcs) -1;
                boolean is_this_a_read=Util.tossWeightedCoin(read_percentage);

                // try the operation until it is successful
                while(true) {
                    try {
                        if(is_this_a_read) {
                            cache.get(key);
                            num_reads.incrementAndGet();
                        }
                        else {
                            cache.put(key, BUFFER);
                            num_writes.incrementAndGet();
                        }
                        if(print > 0 && i % print == 0)
                            System.out.print(".");
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
        long num_gets, num_puts, time;

        public Results() {}

        public Results(int num_gets, int num_puts, long time) {
            this.num_gets=num_gets;
            this.num_puts=num_puts;
            this.time=time;
        }

        public void writeTo(DataOutput out) throws Exception {
            out.writeLong(num_gets);
            out.writeLong(num_puts);
            out.writeLong(time);
        }

        public void readFrom(DataInput in) throws Exception {
            num_gets=in.readLong();
            num_puts=in.readLong();
            time=in.readLong();
        }

        public String toString() {
            long total_reqs=num_gets + num_puts;
            double total_reqs_per_sec=total_reqs / (time / 1000.0);
            return String.format("%.2f reqs/sec (%d GETs, %d PUTs total)", total_reqs_per_sec, num_gets, num_puts);
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
        String           config_file="infinispan.xml";
        String           cache_name="perf-cache";
        String           cache_factory_name="org.cache.impl.InfinispanCacheFactory";
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
                             "Valid factory names: %s (ispn), %s (hc), %s (coh)\n\n",
                          infinispan_factory, hazelcast_factory, coherence_factory);
    }


}