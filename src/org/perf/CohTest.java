package org.perf;


import com.tangosol.net.CacheFactory;
import com.tangosol.net.NamedCache;
import org.infinispan.context.Flag;
import org.jgroups.*;
import org.jgroups.annotations.Property;
import org.jgroups.blocks.*;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.protocols.relay.RELAY2;
import org.jgroups.stack.Protocol;
import org.jgroups.util.*;

import javax.management.MBeanServer;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import java.io.DataInput;
import java.io.DataOutput;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Tests the UNICAST by invoking unicast RPCs between a sender and a receiver. Mimics the DIST mode in Infinispan
 *
 * @author Bela Ban
 */
public class CohTest extends ReceiverAdapter {
    protected JChannel               channel;
    protected Address                local_addr;
    protected RpcDispatcher          disp;
    protected final List<Address>    members=new ArrayList<Address>();
    protected volatile View          view;
    protected final AtomicInteger    num_requests=new AtomicInteger(0);
    protected final AtomicInteger    num_reads=new AtomicInteger(0);
    protected final AtomicInteger    num_writes=new AtomicInteger(0);
    protected volatile boolean       looping=true;
    protected Thread                 event_loop_thread;

    protected NamedCache<Object,Object> cache;


    // ============ configurable properties ==================
    @Property protected boolean sync=true, oob=false;
    @Property protected int     num_threads=25;
    @Property protected int     num_rpcs=20000, msg_size=1000;
    @Property protected int     anycast_count=2;
    @Property protected boolean use_anycast_addrs;
    @Property protected boolean msg_bundling=true;
    @Property protected double  read_percentage=0.8; // 80% reads, 20% writes
    @Property protected boolean get_before_put=false; // invoke a sync GET before a PUT
    // ... add your own here, just don't forget to annotate them with @Property
    // =======================================================

    private static final Method[] METHODS=new Method[16];
    private static final short    START_UPERF           =  0;
    private static final short    START_ISPN            =  1;
    private static final short    GET                   =  2;
    private static final short    PUT                   =  3;
    private static final short    GET_CONFIG            =  4;
    private static final short    SET                   =  5;
    private static final short    QUIT_ALL              =  7;

    private final AtomicInteger   COUNTER=new AtomicInteger(1);
    private byte[]                BUFFER=new byte[msg_size];
    static NumberFormat           f;
    static final Flag[]           async_flags, sync_flags;

    static {
        try {
            METHODS[START_UPERF]  = CohTest.class.getMethod("startUPerfTest");
            METHODS[START_ISPN]   = CohTest.class.getMethod("startIspnTest");
            METHODS[GET]          = CohTest.class.getMethod("get", long.class);
            METHODS[PUT]          = CohTest.class.getMethod("put", long.class, byte[].class);
            METHODS[GET_CONFIG]   = CohTest.class.getMethod("getConfig");
            METHODS[SET]          = CohTest.class.getMethod("set", String.class, Object.class);
            METHODS[QUIT_ALL]     = CohTest.class.getMethod("quitAll");

            ClassConfigurator.add((short)11000, Results.class);
            f=NumberFormat.getNumberInstance();
            f.setGroupingUsed(false);
            f.setMinimumFractionDigits(2);
            f.setMaximumFractionDigits(2);
        }
        catch(NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        sync_flags=new Flag[] {Flag.FORCE_SYNCHRONOUS, Flag.IGNORE_RETURN_VALUES, Flag.SKIP_REMOTE_LOOKUP};
        async_flags=new Flag[] {Flag.FORCE_ASYNCHRONOUS, Flag.IGNORE_RETURN_VALUES, Flag.SKIP_REMOTE_LOOKUP};
    }


    public void init(String cfg, String cache_name, String name, long uuid, int bind_port) throws Throwable {
        try {
            CacheFactory.ensureCluster();
            this.cache=CacheFactory.getCache("coh-perf");

            channel=new JChannel("/home/bela/fast.xml");
            try {
                MBeanServer server=Util.getMBeanServer();
                JmxConfigurator.registerChannel(channel, server, "jgroups", channel.getClusterName(), true);
            }
            catch(Throwable ex) {
                System.err.println("registering the channel in JMX failed: " + ex);
            }

            disp=new RpcDispatcher(channel, null, this, this);
            disp.setMethodLookup(new MethodLookup() {
                public Method findMethod(short id) {
                    return METHODS[id];
                }
            });
            channel.connect("config-cluster");
            local_addr=channel.getAddress();

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
        cache.destroy();
        CacheFactory.shutdown();
    }

    protected void startEventThread() {
        event_loop_thread=new Thread("EventLoop") {
            public void run() {
                try {
                    eventLoop();
                }
                catch(Throwable ex) {
                    ex.printStackTrace();
                    CohTest.this.stop();
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

        System.out.println("invoking " + num_rpcs + " RPCs of " + Util.printBytes(BUFFER.length) + ", sync=" + sync +
                             ", oob=" + oob + ", msg_bundling=" + msg_bundling + ", use_anycast_addrs=" + use_anycast_addrs);
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
        System.out.println("invoking " + num_rpcs + " RPCs of " + Util.printBytes(BUFFER.length)+ ", sync=" + sync );

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
        Util.sleepRandom(10, 10000);
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


    public byte[] get(long key) {
        return BUFFER;
    }


    public void put(long key, byte[] val) {

    }

    public Config getConfig() {
        Config config=new Config();
        for(Field field: Util.getAllDeclaredFields(CohTest.class)) {
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
        int c;

        while(looping) {
            c=Util.keyPress("[1] Start UPerf test [2] Start cache test [3] Print view [4] Print cache size" +
                              "\n[6] Set sender threads (" + num_threads + ") [7] Set num RPCs (" + num_rpcs + ") " +
                              "[8] Set payload size (" + Util.printBytes(msg_size) + ")" +
                              " [9] Set anycast count (" + anycast_count + ")" +
                              "\n[p] Populate cache [c] Clear cache [v] Print versions" +
                              "\n[o] Toggle OOB (" + oob + ") [s] Toggle sync (" + sync +
                              ") [r] Set read percentage (" + f.format(read_percentage) + ") [g] get_before_put (" + get_before_put + ") " +
                              "\n[a] Toggle use_anycast_addrs (" + use_anycast_addrs + ") [b] Toggle msg_bundling (" +
                              (msg_bundling? "on" : "off") + ")" +
                              "\n[q] Quit [X] quit all\n");
            switch(c) {
                case -1:
                    break;
                case '1':
                    startUPerfBenchmark();
                    break;
                case '2':
                    startIspnBenchmark();
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
                case 'a':
                    changeFieldAcrossCluster("use_anycast_addrs", !use_anycast_addrs);
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
                    System.out.println("JGroups: " + Version.printDescription() +
                                         ", Infinispan: " + org.infinispan.Version.printVersion() + "\n");
                    break;
                case 'q':
                    stop();
                    return;
                case 'X':
                    try {
                        RequestOptions options=new RequestOptions(ResponseMode.GET_NONE, 0).setExclusionList(local_addr);
                        options.setFlags(Message.Flag.OOB, Message.Flag.DONT_BUNDLE, Message.Flag.NO_FC);
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
    void startUPerfBenchmark() {
        RspList<Results> responses=null;
        try {
            RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 0);
            options.setFlags(Message.Flag.OOB, Message.Flag.DONT_BUNDLE, Message.Flag.NO_FC);
            responses=disp.callRemoteMethods(null, new MethodCall(START_UPERF), options);
        }
        catch(Throwable t) {
            System.err.println("starting the benchmark failed: " + t);
            return;
        }

        long total_reqs=0;
        long total_time=0;

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
        Protocol prot=channel.getProtocolStack().findProtocol(Util.getUnicastProtocols());
        System.out.println("\n");
        System.out.println(Util.bold("Average of " + f.format(total_reqs_sec) + " requests / sec (" +
                                       Util.printBytes(throughput) + " / sec), " +
                                       f.format(ms_per_req) + " ms /request (prot=" + prot.getName() + ")"));
        System.out.println("\n\n");
    }


    void startIspnBenchmark() {
        RspList<Results> responses=null;
        try {
            RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 0);
            options.setFlags(Message.Flag.OOB, Message.Flag.DONT_BUNDLE, Message.Flag.NO_FC);
            responses=disp.callRemoteMethods(null, new MethodCall(START_ISPN), options);
        }
        catch(Throwable t) {
            System.err.println("starting the benchmark failed: " + t);
            return;
        }

        long total_reqs=0;
        long total_time=0;

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
        Protocol prot=channel.getProtocolStack().findProtocol(Util.getUnicastProtocols());
        System.out.println("\n");
        System.out.println(Util.bold("Average of " + f.format(total_reqs_sec) + " requests / sec (" +
                                       Util.printBytes(throughput) + " / sec), " +
                                       f.format(ms_per_req) + " ms /request (prot=" + prot.getName() + ")"));
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

    protected static List<String> getSites(JChannel channel) {
        RELAY2 relay=(RELAY2)channel.getProtocolStack().findProtocol(RELAY2.class);
        return relay != null? relay.siteNames() : new ArrayList<String>(0);
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
            Transaction tx=null;
            try {
                cache.put(i, BUFFER);
                num_writes.incrementAndGet();
                if(print > 0 && i > 0 && i % print == 0)
                    System.out.print(".");
                if(tx != null)
                    tx.commit();
            }
            catch(Throwable t) {
                t.printStackTrace();
                if(tx != null) {
                    try {
                        tx.rollback();
                    }
                    catch(SystemException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }



    protected  class Invoker extends Thread {
        private final List<Address>  dests=new ArrayList<Address>();
        private final int            num_rpcs_to_invoke;
        private final AtomicInteger  num_rpcs_invoked;
        private int                  num_gets=0;
        private int                  num_puts=0;
        private final int            PRINT;


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
            if(use_anycast_addrs)
                put_options.useAnycastAddresses(true);

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

        private Address pickTarget() {
            return Util.pickRandomElement(dests);
            /*int index=dests.indexOf(local_addr);
            int new_index=(index +1) % dests.size();
            return dests.get(new_index);*/
        }

        private Collection<Address> pickAnycastTargets() {
            Collection<Address> anycast_targets=new ArrayList<Address>(anycast_count);
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
        long num_gets=0;
        long num_puts=0;
        long time=0;

        public Results() {
            
        }

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
            return f.format(total_reqs_per_sec) + " reqs/sec (" + num_gets + " GETs, " + num_puts + " PUTs total)";
        }
    }


    public static class Config implements Streamable {
        protected Map<String,Object> values=new HashMap<String,Object>();

        public Config() {
        }

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
        String           config_file="/home/bela/hazelcast.xml";
        String           cache_name="clusteredCache";
        String           name=null;
        boolean          run_event_loop=true;
        long             uuid=0;
        int              port=0;

        for(int i=0; i < args.length; i++) {
            if(args[i].equals("-cfg")) {
                config_file=args[++i];
                continue;
            }
            if(args[i].equals("-cache")) {
                cache_name=args[++i];
                continue;
            }
            if("-name".equals(args[i])) {
                name=args[++i];
                continue;
            }
            if("-nohup".equals(args[i])) {
                run_event_loop=false;
                continue;
            }
            if("-uuid".equals(args[i])) {
                uuid=Long.parseLong(args[++i]);
                continue;
            }
            if("-port".equals(args[i])) {
                port=Integer.valueOf(args[++i]);
                continue;
            }
            help();
            return;
        }

        CohTest test=null;
        try {
            test=new CohTest();
            test.init(config_file, cache_name, name, uuid, port);
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
        System.out.println("Test [-cfg <config-file>] [-cache <cache-name>] [-name name] [-xsite <true | false>] " +
                             "[-nohup] [-uuid <UUID>] [-port <bind port>]");
    }




}