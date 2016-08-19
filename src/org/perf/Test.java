package org.perf;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.cache.impl.*;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Mimics distributed mode by invoking N (default:50000) requests, 20% writes and 80% reads on random keys in range
 * [1 .. N]. Every member in the cluster does this and the initiator waits until everyone is done, tallies the results
 * (sent to it by every member) and prints stats (throughput).
 * @author Bela Ban
 */
public class Test extends ReceiverAdapter {
    protected CacheFactory<Integer,byte[]> cache_factory;
    protected Cache<Integer,byte[]>        cache;
    protected JChannel                     channel;
    protected Address                      local_addr;
    protected RpcDispatcher                disp;
    protected final List<Address>          members=new ArrayList<>();
    protected volatile View                view;
    protected final AtomicInteger          num_requests=new AtomicInteger(0);
    protected final AtomicInteger          num_reads=new AtomicInteger(0);
    protected final AtomicInteger          num_writes=new AtomicInteger(0);
    protected volatile boolean             looping=true;
    protected Thread                       event_loop_thread;


    // ============ configurable properties ==================
    @Property protected int     num_threads=25;
    @Property protected int     num_keys=50000, num_rpcs=50000, msg_size=1000;
    @Property protected double  read_percentage=0.8; // 80% reads, 20% writes
    @Property protected boolean print_details;
    @Property protected boolean print_invokers;
    // ... add your own here, just don't forget to annotate them with @Property
    // =======================================================

    protected static final Method[] METHODS=new Method[16];
    protected static final short    START_ISPN            =  1;
    protected static final short    GET_CONFIG            =  4;
    protected static final short    SET                   =  5;
    protected static final short    QUIT_ALL              =  6;

    protected byte[]                BUFFER=new byte[msg_size];
    protected static final String   infinispan_factory=InfinispanCacheFactory.class.getName();
    protected static final String   hazelcast_factory=HazelcastCacheFactory.class.getName();
    protected static final String   coherence_factory="org.cache.impl.coh.CoherenceCacheFactory"; // to prevent loading of Coherence up-front
    protected static final String   jg_factory=JGroupsCacheFactory.class.getName();
    protected static final String   dist_factory=DistCacheFactory.class.getName();
    protected static final String   tri_factory=TriCache.class.getName();

    protected static final String input_str="[1] Start cache test [2] View [3] Cache size" +
      "\n[4] Sender threads (%d) [5] Num keys (%d) [6] Num RPCs (%d) [7] Msg size (%s)" +
      "\n[p] Populate cache [c] Clear cache [v] Versions" +
      "\n[r] Read percentage (%.2f) " +
      "\n[d] Details (%b)  [i] Invokers (%b)" +
      "\n[q] Quit [X] Quit all\n";

    static {
        try {
            METHODS[START_ISPN]   = Test.class.getMethod("startIspnTest");
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
            channel.connect("cfg");
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


    public Results startIspnTest() throws Throwable {
        num_requests.set(0);
        num_reads.set(0);
        num_writes.set(0);

        try {
            BUFFER=new byte[msg_size];
            System.out.printf("invoking %d RPCs of %s\n", num_rpcs, Util.printBytes(BUFFER.length));

            // The first call needs to be synchronous with OOB !
            final CountDownLatch latch=new CountDownLatch(1);
            CacheInvoker[] invokers=new CacheInvoker[num_threads];
            for(int i=0; i < invokers.length; i++) {
                invokers[i]=new CacheInvoker(latch);
                invokers[i].setName("invoker-" + i);
                invokers[i].start();
            }

            long start=System.currentTimeMillis();
            latch.countDown(); // starts all sender threads

            for(CacheInvoker invoker : invokers)
                invoker.join();
            long time=System.currentTimeMillis() - start;
            System.out.println("\ndone (in " + time + " ms)\n");

            AverageMinMax get_avg=null, put_avg=null;
            if(print_invokers)
                System.out.printf("Round trip times (min/avg/max us):\n");
            for(CacheInvoker inv: invokers) {
                if(print_invokers)
                    System.out.printf("%s: get %.2f / %.2f / %.2f, put: %.2f / %.2f / %.2f\n", inv.getId(),
                                      inv.get_avg.min()/1000.0, inv.get_avg.average()/1000.0, inv.get_avg.max()/1000.0,
                                      inv.put_avg.min()/1000.0, inv.put_avg.average()/1000.0, inv.put_avg.max()/1000.0);
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
                                  get_avg.min()/1000.0, get_avg.average()/1000.0, get_avg.max()/1000.0,
                                  put_avg.min()/1000.0, put_avg.average()/1000.0, put_avg.max()/1000.0);
            return new Results(num_reads.get(), num_writes.get(), time, get_avg, put_avg);
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
        Field field=Util.getField(this.getClass(),field_name);
        if(field == null)
            System.err.println("Field " + field_name + " not found");
        else {
            Util.setField(field, this, value);
            System.out.println(field.getName() + "=" + value);
        }
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
                                              num_threads, num_keys, num_rpcs, Util.printBytes(msg_size),
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
                    changeFieldAcrossCluster("num_rpcs", Util.readIntFromStdin("Number of RPCs: "));
                    break;
                case '7':
                    changeFieldAcrossCluster("msg_size", Util.readIntFromStdin("Message size: "));
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
                    double percentage= getReadPercentage();
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
        AverageMinMax get_avg=null, put_avg=null;
        System.out.println("\n======================= Results: ===========================");
        for(Map.Entry<Address,Rsp<Results>> entry: responses.entrySet()) {
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
        double total_reqs_sec=total_reqs / ( total_time/ 1000.0);
        double throughput=total_reqs_sec * msg_size;
        System.out.println("\n");
        System.out.println(Util.bold(String.format("Throughput: %.2f reqs/sec/node (%s/sec)\n" +
                                                     "Roundtrip:  gets %s, puts %s\n",
                                                   total_reqs_sec, Util.printBytes(throughput),
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

    protected static String print(AverageMinMax avg, boolean details) {
        return details? String.format("min/avg/max = %.2f/%.2f/%.2f us",
                                      avg.min() / 1000.0, avg.average() / 1000.0, avg.max() / 1000.0) :
          String.format("avg = %.2f us", avg.average() / 1000.0);
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
        int    print=num_keys / 10;
        for(int i=1; i <= num_keys; i++) {
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



    protected class CacheInvoker extends Thread {
        protected final CountDownLatch latch;
        protected final int            print=num_rpcs / 10;
        protected final AverageMinMax  get_avg=new AverageMinMax(); // in ns
        protected final AverageMinMax  put_avg=new AverageMinMax(); // in ns


        public CacheInvoker(CountDownLatch latch) {
            this.latch=latch;
        }

        public void run() {
            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
            }
            for(;;) {
                int i=num_requests.incrementAndGet();
                if(i > num_rpcs) {
                    num_requests.decrementAndGet();
                    return;
                }

                // get a random key in range [0 .. num_rpcs-1]
                int key=(int)Util.random(num_keys) -1;
                boolean is_this_a_read=Util.tossWeightedCoin(read_percentage);

                // try the operation until it is successful
                while(true) {
                    try {
                        if(is_this_a_read) {
                            long start=System.nanoTime();
                            cache.get(key);
                            long time=System.nanoTime() - start;
                            get_avg.add(time);
                            num_reads.incrementAndGet();
                        }
                        else {
                            long start=System.nanoTime();
                            cache.put(key, BUFFER);
                            long time=System.nanoTime() - start;
                            put_avg.add(time);
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
        long num_gets, num_puts, time; // ms
        AverageMinMax get_avg, put_avg;

        public Results() {}

        public Results(int num_gets, int num_puts, long time, AverageMinMax get_avg, AverageMinMax put_avg) {
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
            get_avg=(AverageMinMax)Util.readStreamable(AverageMinMax.class, in);
            put_avg=(AverageMinMax)Util.readStreamable(AverageMinMax.class, in);
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
                case "jg":
                    cache_factory_name=jg_factory;
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
                            "\n  ispn: %s\n  hc:   %s\n  coh:  %s\n  jg:   %s\n  dist: %s\n  tri:  %s\n\n",
                          infinispan_factory, hazelcast_factory, coherence_factory, jg_factory, dist_factory, tri_factory);
    }


}