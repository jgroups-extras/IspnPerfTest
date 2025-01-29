package org.perf;

import org.HdrHistogram.Histogram;
import org.cache.Cache;
import org.cache.CacheFactory;
import org.cache.impl.*;
import org.cache.impl.tri.TriCacheFactory;
import org.infinispan.commons.jdkspecific.ThreadCreator;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.*;
import org.jgroups.annotations.Property;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.protocols.TP;
import org.jgroups.util.UUID;
import org.jgroups.util.*;

import javax.management.MBeanServer;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.DataFormatException;


/**
 * Mimics distributed mode by invoking N (default:50000) requests, 20% writes and 80% reads on random keys in range
 * [1 .. N]. Every member in the cluster does this and the initiator waits until everyone is done, tallies the results
 * (sent to it by every member) and prints stats (throughput).
 * @author Bela Ban
 */
public class Test implements Receiver {
    private static final String NODE_STARTED_MESSAGE = "Node completed start setup";
    private static final String TEST_FINISHED_MESSAGE = "Test execution completed";

    protected CacheFactory<Integer,byte[]>        cache_factory;
    protected Cache<Integer,byte[]>               cache;
    protected JChannel                            control_channel;
    protected String                              cfg="dist-sync.xml", control_cfg="control.xml";
    protected Address                             local_addr;
    protected final List<Address>                 members=new ArrayList<>();
    protected volatile View                       view;
    protected final LongAdder                     num_requests=new LongAdder();
    protected final LongAdder                     num_reads=new LongAdder();
    protected final LongAdder                     num_writes=new LongAdder();
    protected volatile boolean                    looping=true;
    protected Integer[]                           keys;
    protected final ResponseCollector<Results>    results=new ResponseCollector<>();
    protected final Promise<Map<Integer,byte[]>>  contents_promise=new Promise<>();
    protected final Promise<Config>               config_promise=new Promise<>();
    protected Thread                              test_runner;
    protected ThreadFactory                       thread_factory;
    protected boolean                             done; // set to true if batch-mode and run was triggered

    protected enum Type {
        START_ISPN,
        GET_CONFIG_REQ,
        GET_CONFIG_RSP,       // Config
        SET,                  // field-name (String), value (Object)
        GET_CONTENTS_REQ,
        GET_CONTENTS_RSP,     // Map<Integer,byte[]>
        QUIT_ALL,
        RESULTS               // Results
    }

    // ============ configurable properties ==================
    @Property protected int     num_threads=100;
    @Property protected int     num_keys=100000;
    @Property protected int     time=60, warmup; // in secs
    @Property protected int     msg_size=1000;
    @Property protected boolean batch_mode;
    @Property protected int     num_nodes;
    @Property protected double  read_percentage=0.8; // 80% reads, 20% writes
    @Property protected String  result_file="results.txt";
    @Property protected String  results_file_csv="results.csv";
    @Property protected String  csv_filter;
    @Property protected boolean print_details=true;
    @Property protected boolean print_invokers;
    // ... add your own here, just don't forget to annotate them with @Property
    // =======================================================

    // 3 longs at the start of each buffer for validation
    protected static final int    VALIDATION_SIZE=Global.LONG_SIZE *3;

    public static final double[]  PERCENTILES={50, 90, 95, 99, 99.9};

    protected static final String infinispan_factory=InfinispanCacheFactory.class.getName();
    protected static final String hazelcast_factory=HazelcastCacheFactory.class.getName();
    protected static final String coherence_factory="org.cache.impl.CoherenceCacheFactory"; // to prevent loading of Coherence up-front
    protected static final String tri_factory=TriCacheFactory.class.getName();
    protected static final String dummy_factory=DummyCacheFactory.class.getName();
    protected static final String raft_factory=RaftCacheFactory.class.getName();
    protected static final String local_factory=LocalCacheFactory.class.getName();

    protected static final String input_str="[1] Start test [2] View [3] Cache size [4] Threads (%d) " +
      "\n[5] Keys (%,d) [6] Time (secs) (%d) [7] Value size (%s) [8] Validate" +
      "\n[p] Populate cache [c] Clear cache [v] Versions" +
      "\n[r] Read percentage (%.2f) " +
      "\n[d] Details (%b)  [i] Invokers (%b) [l] dump local cache" +
      "\n[x] Exit [X] Exit all\n";

    static {
        ClassConfigurator.add((short)11000, Results.class);
    }

    public Test cfg(String c)            {this.cfg=c; return this;}
    public Test controlCfg(String c)     {this.control_cfg=c; return this;}
    public Test numThreads(int n)        {this.num_threads=n; return this;}
    public Test numKeys(int n)           {this.num_keys=n; return this;}
    public Test time(int t)              {this.time=t; return this;}
    public Test warmup(int t)            {this.warmup=t; return this;}
    public Test msgSize(int s)           {this.msg_size=s; return this;}
    public Test numNodes(int n)          {this.num_nodes=n; return this;}
    public Test readPercentage(double p) {this.read_percentage=p; return this;}
    public Test resultFile(String f)     {this.result_file=f; return this;}
    public Test batchMode(boolean b)     {this.batch_mode=b; return this;}
    public Test csvFilter(String f)      {this.csv_filter=f; return this;}

    public void init(String factory, String cache_name, boolean use_vthreads) throws Exception {
        // sanity checks:
        if(batch_mode) {
            if(num_nodes <= 0)
                throw new IllegalStateException("nodes cannot be 0 when batch-mode is enabled");
        }

        thread_factory=new DefaultThreadFactory("invoker", false, true).useVirtualThreads(use_vthreads);
        if(use_vthreads && Util.virtualThreadsAvailable())
            System.out.println("-- using virtual threads");

        Class<CacheFactory<Integer,byte[]>> clazz=(Class<CacheFactory<Integer,byte[]>>)Util.loadClass(factory, (Class<?>)null);
        cache_factory=clazz.getDeclaredConstructor().newInstance();
        cache_factory.init(cfg);
        cache=cache_factory.create(cache_name);

        control_channel=new JChannel(control_cfg);
        control_channel.setReceiver(this);
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
            config_promise.reset(true);
            send(coord, Type.GET_CONFIG_REQ);
            Config config=config_promise.getResult(5000);
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
        System.out.printf("created %,d keys: [%,d-%,d]\n", keys.length, keys[0], keys[keys.length - 1]);
        System.out.println(NODE_STARTED_MESSAGE);

        if (num_nodes == 1 && view == null) {
            viewAccepted(control_channel.view());
        }
    }

    protected void stop() {
        Util.close(control_channel);
        if(cache_factory != null)
            cache_factory.destroy();
    }

    protected synchronized void startTestRunner(final Address addr, int time_secs) {
        if(test_runner != null && test_runner.isAlive())
            System.err.println("test is already running - wait until complete to start a new run");
        else {
            test_runner=new Thread(() -> startIspnTest(addr, time_secs), "testrunner");
            test_runner.start();
        }
    }

    protected static Integer[] createKeys(int num_keys) {
        Integer[] retval=new Integer[num_keys];
        for(int i=0; i < num_keys; i++)
            retval[i]=i + 1;
        return retval;
    }

    public void receive(Message msg) {
        try {
            _receive(msg);
        }
        catch(Throwable t) {
            t.printStackTrace();
        }
    }

    protected void _receive(Message msg) throws Throwable {
        ByteArrayDataInputStream in;
        Address sender=msg.src();
        int offset=msg.getOffset(), len=msg.getLength();
        byte[] buf=msg.getArray();
        byte t=buf[offset++];
        len--;
        Type type=Type.values()[t];
        switch(type) {
            case START_ISPN:
                int time_secs=Util.primitiveFromStream(new ByteArrayDataInputStream(buf, offset, 5));
                startTestRunner(sender, time_secs);
                break;
            case GET_CONFIG_REQ:
                send(sender, Type.GET_CONFIG_RSP, getConfig());
                break;
            case GET_CONFIG_RSP:
                in=new ByteArrayDataInputStream(buf, offset, len);
                Config c=Util.objectFromStream(in);
                config_promise.setResult(c);
                break;
            case SET:
                in=new ByteArrayDataInputStream(buf, offset, len);
                String field_name=Util.objectFromStream(in);
                Object val=Util.objectFromStream(in);
                set(field_name, val);
                break;
            case GET_CONTENTS_REQ:
                send(sender, Type.GET_CONTENTS_RSP, getContents());
                break;
            case GET_CONTENTS_RSP:
                in=new ByteArrayDataInputStream(buf, offset, len);
                Map<Integer,byte[]> map=Util.objectFromStream(in);
                contents_promise.setResult(map);
                break;
            case QUIT_ALL:
                quitAll();
                break;
            case RESULTS:
                in=new ByteArrayDataInputStream(buf, offset, len);
                Results res=Util.objectFromStream(in);
                results.add(sender, res);
                break;
            default:
                throw new IllegalArgumentException(String.format("type %s not known", type));
        }
    }

    public void viewAccepted(View new_view) {
        this.view=new_view;
        members.clear();
        members.addAll(new_view.getMembers());

        if(!batch_mode || done)
            return;

        boolean is_coord=Objects.equals(new_view.getCoord(), local_addr);
        if(new_view.size() < num_nodes && is_coord) {
            System.out.printf("-- new view has %d members; waiting for %d\n", new_view.size(), num_nodes);
            return;
        }
        // more than num_nodes members and am I the coord to start the batch?
        if(new_view.size() >= num_nodes && is_coord) {
            System.out.printf("-- reached expected number of members (%d): starting test in batch-mode\n", num_nodes);
            new Thread(this::runBatch).start();
            done=true;
        }
    }

    protected void startIspnTest(Address sender, int time_secs) {
        num_requests.reset();
        num_reads.reset();
        num_writes.reset();

        try {
            System.out.printf("Running test for %d seconds:\n", time_secs);

            // The first call needs to be synchronous with OOB !
            final CountDownLatch latch=new CountDownLatch(1);
            CacheInvoker[] invokers=new CacheInvoker[num_threads];
            Thread[]       threads=new Thread[num_threads];
            for(int i=0; i < invokers.length; i++) {
                invokers[i]=new CacheInvoker(latch);
                threads[i]=thread_factory.newThread(invokers[i]);
                threads[i].setName("invoker-" + i);
                threads[i].start();
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
                    System.out.print(".");
            }

            Arrays.stream(invokers).forEach(CacheInvoker::cancel);
            for(Thread t: threads)
                t.join();

            long t=System.currentTimeMillis() - start;
            System.out.println("\ndone (in " + t + " ms)\n");

            Histogram get_avg=null, put_avg=null;
            if(print_invokers)
                System.out.print("Round trip times (min/avg/max us):\n");
            for(int i=0; i < invokers.length; i++) {
                CacheInvoker inv=invokers[i];
                if(print_invokers)
                    System.out.printf("%s: get %d / %,.2f / %,.2f, put: %d / %,.2f / %,.2f\n", threads[i].getId(),
                                      inv.get_avg.getMinValue(), inv.get_avg.getMean(), inv.get_avg.getMaxValueAsDouble(),
                                      inv.put_avg.getMinValue(), inv.put_avg.getMean(), inv.put_avg.getMaxValueAsDouble());
                if(get_avg == null)
                    get_avg=inv.get_avg;
                else
                    get_avg.add(inv.get_avg);
                if(put_avg == null)
                    put_avg=inv.put_avg;
                else
                    put_avg.add(inv.put_avg);
            }
            if(print_details || print_invokers)
                System.out.printf("\nall: get %d / %,.2f / %,.2f, put: %d / %,.2f / %,.2f\n",
                                  get_avg.getMinValue(), get_avg.getMean(), get_avg.getMaxValueAsDouble(),
                                  put_avg.getMinValue(), put_avg.getMean(), put_avg.getMaxValueAsDouble());
            Results result=new Results(num_reads.sum(), num_writes.sum(), t, get_avg, put_avg);
            send(sender, Type.RESULTS, result);
        }
        catch(Throwable t) {
            t.printStackTrace();
        }
    }

    // Only run when coordinator
    protected void runBatch() {
        try {
            System.out.printf("-- Populating cache with %d keys:\n", num_keys);
            populateCache();
            System.out.println();
            if(warmup > 0)
                startBenchmark(warmup, false);
            Util.sleep(2000);
            startBenchmark(time, true);
            Util.sleep(2000);
            control_channel.send(null, new byte[]{(byte)Type.QUIT_ALL.ordinal()});
        }
        catch(Throwable t) {
            System.err.println("Calling quitAll() failed: " + t);
        } finally {
            System.out.println(TEST_FINISHED_MESSAGE);
        }
    }

    protected void quitAll() {
        System.out.println("-- received quitAll(): shutting down");
        looping=false;
        stop();
        System.exit(0);
    }

    protected void set(String field_name, Object value) {
        Field field=Util.getField(this.getClass(), field_name);
        if(field == null)
            System.err.println("Field " + field_name + " not found");
        else {
            Util.setField(field, this, value);
            System.out.println(field.getName() + "=" + value);
        }
        changeKeySet();
    }

    protected Config getConfig() {
        Config config=new Config();
        for(Field field: Util.getAllDeclaredFieldsWithAnnotations(Test.class, Property.class))
            config.add(field.getName(), Util.getField(field, this));
        return config;
    }

    protected void applyConfig(Config config) {
        for(Map.Entry<String,Object> entry : config.values.entrySet()) {
            Field field=Util.getField(getClass(), entry.getKey());
            Util.setField(field, this, entry.getValue());
        }
        changeKeySet();
    }

    protected Map<Integer,byte[]> getContents() {
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
                                              num_threads, keys != null? keys.length : 0, time, Util.printBytes(msg_size),
                                              read_percentage, print_details, print_invokers));
            switch(c) {
                case '1':
                    startBenchmark(time, false);
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
                    changeFieldAcrossCluster("time", Util.readIntFromStdin("Time (secs): "));
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
                                      org.jgroups.Version.printDescription(),
                                      org.infinispan.commons.util.Version.printVersion());
                    break;
                case 'x':
                case 0: // remove on upgrade to next JGroups version
                case -1:
                    stop();
                    return;
                case 'l':
                    dumpLocalCache();
                    break;
                case 'X':
                    try {
                        control_channel.send(null, new byte[]{(byte)Type.QUIT_ALL.ordinal()});
                    }
                    catch(Throwable t) {
                        System.err.println("Calling quitAll() failed: " + t);
                    }
                    break;
                default:
                    break;
            }
        }
    }


    /** Kicks off the benchmark on all cluster nodes */
    protected void startBenchmark(int time_secs, boolean write_results) {
        results.reset(members);
        try {
            send(null, Type.START_ISPN, time_secs);
        }
        catch(Throwable t) {
            System.err.printf("starting the benchmark failed: %s\n", t);
            return;
        }

        // wait for time_secs seconds pus 20%
        boolean all_results=results.waitForAllResponses((long)(time_secs * 1000 * 1.2));
        if(!all_results)
            System.err.printf("did not receive all results: missing results from %s\n", results.getMissing());

        long total_reqs=0, total_time=0, longest_time=0;
        Histogram get_avg=null, put_avg=null;
        System.out.println("\n======================= Results: ===========================");
        for(Map.Entry<Address,Results>  entry: results.getResults().entrySet()) {
            Address mbr=entry.getKey();
            Results result=entry.getValue();
            if(result != null) {
                total_reqs+=result.num_gets + result.num_puts;
                total_time+=result.time;
                longest_time=Math.max(longest_time, result.time);
                if(get_avg == null)
                    get_avg=result.get_avg;
                else
                    get_avg.add(result.get_avg);
                if(put_avg == null)
                    put_avg=result.put_avg;
                else
                    put_avg.add(result.put_avg);
            }
            System.out.println(mbr + ": " + result);
        }
        double reqs_sec_node=total_reqs / (total_time / 1000.0);
        double reqs_sec_cluster=total_reqs / (longest_time / 1000.0);
        double throughput=reqs_sec_node * msg_size;
        String result=String.format("\nThroughput: %,.0f reqs/sec/node (%s/sec) %,.0f reqs/sec/cluster\n" +
                                      "RTT GETs:   %s,\n" +
                                      "RTT PUTs:   %s\n\n",
                                    reqs_sec_node, Util.printBytes(throughput), reqs_sec_cluster,
                                    print(get_avg, print_details), print(put_avg, print_details));
        System.out.println(Util.bold(result));
        if(batch_mode && write_results) {
            Map<String,Object> map=envMap();
            try(DataOutputStream out=new DataOutputStream(new FileOutputStream(result_file, true))) {
                String s=env(map);
                byte[] bytes=s.getBytes();
                out.write(bytes, 0, bytes.length);
                bytes=result.getBytes();
                out.write(bytes, 0, bytes.length);
            }
            catch(IOException e) {
                throw new RuntimeException(e);
            }
            if(csv_filter != null) {
                File f=new File(results_file_csv);
                boolean add_header=!f.exists();
                try(DataOutputStream out=new DataOutputStream(new FileOutputStream(results_file_csv, true))) {
                    if(add_header) {
                        String header="Description, Throughput\n";
                        byte[] bytes=header.getBytes();
                        out.write(bytes, 0, bytes.length);
                    }
                    String s=csv(map, csv_filter);
                    byte[] bytes=s.getBytes();
                    out.write(bytes, 0, bytes.length);
                    s=String.format(", %.2f\n", reqs_sec_node);
                    bytes=s.getBytes();
                    out.write(bytes, 0, bytes.length);
                }
                catch(IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    protected static double getReadPercentage() throws Exception {
        double tmp=Util.readDoubleFromStdin("Read percentage: ");
        if(tmp < 0 || tmp > 1.0) {
            System.err.println("read percentage must be >= 0 or <= 1.0");
            return -1;
        }
        return tmp;
    }

    protected int getAnycastCount() throws Exception {
        int tmp=Util.readIntFromStdin("Anycast count: ");
        View tmp_view=control_channel.getView();
        if(tmp > tmp_view.size()) {
            System.err.println("anycast count must be smaller or equal to the view size (" + tmp_view + ")\n");
            return -1;
        }
        return tmp;
    }

    protected void changeFieldAcrossCluster(String field_name, Object value) throws Exception {
        send(null, Type.SET, field_name, value);
    }

    protected void send(Address dest, Type type, Object ... args) throws Exception {
        if(args == null || args.length == 0) {
            control_channel.send(dest, new byte[]{(byte)type.ordinal()});
            return;
        }
        ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(512);
        out.write((byte)type.ordinal());
        for(Object arg: args)
            Util.objectToStream(arg, out);
        control_channel.send(dest, out.buffer(), 0, out.position());
    }

    protected void printView() {
        System.out.printf("\n-- local: %s\n-- view: %s\n", local_addr, view);
        try {
            System.in.skip(System.in.available());
        }
        catch(Exception e) {
        }
    }

    protected static String print(Histogram avg, boolean details) {
        if(avg == null || avg.getTotalCount() == 0)
            return "n/a";
        return details? String.format("min/avg/max = %d/%,.2f/%,.2f us (%s)",
                                      avg.getMinValue(), avg.getMean(), avg.getMaxValueAsDouble(), percentiles(avg)) :
          String.format("avg = %,.2f us", avg.getMean());
    }

    public static String env(Map<String,Object> m) {
        StringBuilder sb=new StringBuilder(String.format("\n-------------------- %s -------------------------\n", m.get("date")));
        String fmt="node: %s\nip: %s\nview: %s\njgroups: %s\ninfinispan: %s\njdk: %s\njg-vthreads: %b\n" +
          "ispn-vthreads: %b\ncfg: %s\ncontrol_cfg: %s\nnum_threads: %d\nnum_keys: %,d\ntime: %s\nwarmup: %s\n" +
          "msg_size: %s\nnodes: %d\nread_percentage: %.2f\n";
        sb.append(String.format(fmt, m.get("node"), m.get("ip"),
                                m.get("view"), m.get("jg"), m.get("ispn"),
                                m.get("jdk"), m.get("jg-vthreads"), m.get("ispn-vthreads"),
                                m.get("cfg"), m.get("control-cfg"),
                                m.get("threads"), m.get("keys"), Util.printTime((Integer)m.get("time"), TimeUnit.SECONDS),
                                Util.printTime((Integer)m.get("warmup"), TimeUnit.SECONDS),
                                Util.printBytes((Integer)m.get("msg-size")),
                                m.get("nodes"), m.get("read-percentage")));
        return sb.toString();
    }

    public static String csv(Map<String,Object> m, String filter) {
        StringBuilder sb=new StringBuilder(String.format("%s", m.get("date")));
        StringTokenizer tok=new StringTokenizer(filter, ",");
        while(tok.hasMoreTokens()) {
            String key=tok.nextToken();
            Object val=m.get(key);
            if(val != null)
                sb.append(String.format(" %s=%s", key, val));
        }
        return sb.toString();
    }

    public Map<String,Object> envMap() {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("date", getDate());
        m.put("node", realAddress());
        m.put("ip", physicalAddress());
        m.put("view", clusterView());
        m.put("jdk", Util.JAVA_VERSION);
        m.put("jg", org.jgroups.Version.printVersion());
        m.put("ispn", String.format("%s", org.infinispan.commons.util.Version.getBrandVersion()));
        m.put("jg-vthreads", jgroupsVThreads());
        m.put("ispn-vthreads", ispnVThrads());
        m.put("cfg", cfg);
        m.put("control-cfg", control_cfg);
        m.put("threads", num_threads);
        m.put("keys", num_keys);
        m.put("warmup", warmup);
        m.put("time", time);
        m.put("nodes", num_nodes);
        m.put("msg-size", msg_size);
        m.put("read-percentage", read_percentage);
        return m;
    }

    protected static String getDate() {
        LocalDate d=LocalDate.now();
        LocalTime t=LocalTime.now().truncatedTo(ChronoUnit.SECONDS);
        return String.format("%s %s", d, t);
    }

    protected String realAddress() {
        if(cache instanceof InfinispanCache) {
            JGroupsTransport transport=(JGroupsTransport)((InfinispanCache<?,?>)cache).getTransport();
            org.infinispan.remoting.transport.Address addr=transport.getAddress();
            return addr != null? addr.toString() : "n/a";
        }
        return "n/a";
    }

    protected String physicalAddress() {
        if(cache instanceof InfinispanCache) {
            JGroupsTransport transport=(JGroupsTransport)((InfinispanCache<?,?>)cache).getTransport();
            JChannel ch=transport.getChannel();
            Address addr=ch != null? (Address)ch.down(new Event(Event.GET_PHYSICAL_ADDRESS, ch.address())) : null;
            return addr != null? addr.toString() : "n/a";
        }
        return "n/a";
    }

    protected String clusterView() {
        if(cache instanceof InfinispanCache) {
            JGroupsTransport transport=(JGroupsTransport)((InfinispanCache<?,?>)cache).getTransport();
            JChannel ch=transport.getChannel();
            View cluster_view=ch != null? ch.getView() : null;
            return cluster_view != null? cluster_view.toString() : "n/a";
        }
        return "n/a";
    }

    protected boolean jgroupsVThreads() {
        if(cache instanceof InfinispanCache) {
            JGroupsTransport transport=(JGroupsTransport)((InfinispanCache<?,?>)cache).getTransport();
            JChannel ch=transport.getChannel();
            TP tp=ch.getProtocolStack().getTransport();
            return tp.useVirtualThreads();
        }
        return false;
    }

    protected static boolean ispnVThrads() {
        // kludge ahead:
        Optional<ExecutorService> blocking_executor=ThreadCreator.createBlockingExecutorService();
        return blocking_executor.isPresent();
    }

    protected static String percentiles(Histogram h) {
        StringBuilder sb=new StringBuilder();
        for(double percentile: PERCENTILES) {
            long val=h.getValueAtPercentile(percentile);
            sb.append(String.format("%,.1f=%,d ", percentile, val));
        }
        sb.append(String.format("[percentile at mean: %,.2f]", h.getPercentileAtOrBelowValue((long)h.getMean())));
        return sb.toString();
    }

    protected String printAverage(long start_time) {
        long t=System.currentTimeMillis() - start_time;
        long reads=num_reads.sum(), writes=num_writes.sum();
        double reqs_sec=num_requests.sum() / (t / 1000.0);
        return String.format("%,.0f reqs/sec (%,d reads %,d writes)", reqs_sec, reads, writes);
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
            System.out.printf("created %,d keys: [%,d-%,d], old key set size: %,d\n",
                              keys.length, keys[0], keys[keys.length - 1], old_key_size);
        }
    }

    // Inserts num_keys keys into the cache (in parallel)
    protected void populateCache() throws InterruptedException {
        final AtomicInteger key=new AtomicInteger(1);
        final int           print=num_keys / 10;
        final UUID          local_uuid=(UUID)local_addr;
        final AtomicInteger count=new AtomicInteger(1);

        Thread[] inserters=new Thread[num_threads];
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
                    contents_promise.reset(false);
                    send(mbr, Type.GET_CONTENTS_REQ);
                    mbr_map=contents_promise.getResult(60000);
                }
                catch(Throwable t) {
                    System.err.printf("failed fetching contents from %s: %s\n", mbr, t);
                    return; // return from validation
                }
            }

            int size=mbr_map.size(), errors=0;
            total_keys+=size;

            System.out.printf("-- Validating contents of %s (%,d keys): ", mbr, size);
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
                System.out.print("OK\n");
        }
        System.out.println(Util.bold(String.format("\nValidated %,d keys total, %,d errors\n\n", total_keys, tot_errors)));
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
        System.out.printf("\n%,d local values found\n", local_cache.size());
    }

    protected class CacheInvoker implements Runnable {
        protected final CountDownLatch latch;
        // max recordable value is 80s
        protected final Histogram      get_avg=new Histogram(1, 80_000_000, 3); // us
        protected final Histogram      put_avg=new Histogram(1, 80_000_000, 3); // us
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
                            long start=Util.micros();
                            cache.get(key);
                            long t=Util.micros() - start;
                            get_avg.recordValue(t);
                            num_reads.increment();
                        }
                        else {
                            byte[] buffer=new byte[msg_size];
                            writeTo(local_uuid, count++, buffer, 0);
                            long start=Util.micros();
                            cache.put(key, buffer);
                            long t=Util.micros() - start;
                            put_avg.recordValue(t);
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
        protected long      num_gets, num_puts, time; // ms
        protected Histogram get_avg, put_avg;

        public Results() {}

        public Results(long num_gets, long num_puts, long time, Histogram get_avg, Histogram put_avg) {
            this.num_gets=num_gets;
            this.num_puts=num_puts;
            this.time=time;
            this.get_avg=get_avg;
            this.put_avg=put_avg;
        }

        public void writeTo(DataOutput out) throws IOException {
            out.writeLong(num_gets);
            out.writeLong(num_puts);
            out.writeLong(time);
            write(get_avg, out);
            write(put_avg, out);
        }

        public void readFrom(DataInput in) throws IOException {
            num_gets=in.readLong();
            num_puts=in.readLong();
            time=in.readLong();
            try {
                get_avg=read(in);
                put_avg=read(in);
            }
            catch(DataFormatException dfex) {
                throw new IOException(dfex);
            }
        }

        public String toString() {
            long total_reqs=num_gets + num_puts;
            double total_reqs_per_sec=total_reqs / (time / 1000.0);
            return String.format("%,.2f reqs/sec (%,d GETs, %,d PUTs), avg RTT (us) = %,.2f get, %,.2f put",
                                 total_reqs_per_sec, num_gets, num_puts, get_avg.getMean(), put_avg.getMean());
        }
    }

    protected static void write(Histogram h, DataOutput out) throws IOException {
        int size=h.getEstimatedFootprintInBytes();
        ByteBuffer buf=ByteBuffer.allocate(size);
        h.encodeIntoCompressedByteBuffer(buf, 9);
        out.writeInt(buf.position());
        out.write(buf.array(), 0, buf.position());
    }

    protected static Histogram read(DataInput in) throws IOException, DataFormatException {
        int len=in.readInt();
        byte[] array=new byte[len];
        in.readFully(array);
        ByteBuffer buf=ByteBuffer.wrap(array);
        return Histogram.decodeFromCompressedByteBuffer(buf, 0);
    }


    public static class Config implements Streamable {
        protected final Map<String,Object> values=new HashMap<>();

        public Config() {}

        public Config add(String key, Object value) {
            values.put(key, value);
            return this;
        }

        public void writeTo(DataOutput out) throws IOException {
            out.writeInt(values.size());
            for(Map.Entry<String,Object> entry: values.entrySet()) {
                Bits.writeString(entry.getKey(), out);
                Util.objectToStream(entry.getValue(), out);
            }
        }

        public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
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
        String  cache_name="perf-cache";
        String  cache_factory_name=InfinispanCacheFactory.class.getName();
        boolean interactive=true, use_vthreads=true;

        Test test=new Test();
        for(int i=0; i < args.length; i++) {
            if(args[i].equals("-cfg")) {
                test.cfg(args[++i]);
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
                interactive=false;
                continue;
            }
            if("-control-cfg".equals(args[i])) {
                test.controlCfg(args[++i]);
                continue;
            }
            if("-use-virtual-threads".equals(args[i]) || "-use-vthreads".equals(args[i])) {
                use_vthreads=Boolean.parseBoolean(args[++i]);
                continue;
            }
            if("-threads".equals(args[i])) {
                test.numThreads(Integer.parseInt(args[++i]));
                continue;
            }
            if("-keys".equals(args[i])) {
                test.numKeys(Integer.parseInt(args[++i]));
                continue;
            }
            if("-time".equals(args[i])) {
                test.time(Integer.parseInt(args[++i]));
                continue;
            }
            if("-warmup".equals(args[i])) {
                test.warmup(Integer.parseInt(args[++i]));
                continue;
            }
            if("-msg-size".equals(args[i])) {
                test.msgSize(Integer.parseInt(args[++i]));
                continue;
            }
            if("-nodes".equals(args[i])) {
                test.numNodes(Integer.parseInt(args[++i]));
                continue;
            }
            if("-read-percentage".equals(args[i])) {
                test.readPercentage(Double.parseDouble(args[++i]));
                continue;
            }
            if("-batch-mode".equals(args[i])) {
                test.batchMode(Boolean.parseBoolean(args[++i]));
                continue;
            }
            if("-result-file".equals(args[i])) {
                test.resultFile(args[++i]);
                continue;
            }
            if("-csv".equals(args[i])) {
                test.csvFilter(args[++i]);
                continue;
            }
            if("-env".equals(args[i])) {
                String env=Test.env(test.envMap());
                System.out.println("env = " + env);
                return;
            }
            help();
            return;
        }

        try {
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
                case "tri":
                    cache_factory_name=tri_factory;
                    break;
                case "dummy":
                    cache_factory_name=dummy_factory;
                    break;
                case "raft":
                    cache_factory_name=raft_factory;
                    break;
                case "local":
                    cache_factory_name=local_factory;
            }
            test.init(cache_factory_name, cache_name, use_vthreads);
            Runtime.getRuntime().addShutdownHook(new Thread(test::stop));
            if(!interactive || test.batch_mode) {
                for(;;)
                    Util.sleep(60_000);
            }
            else
                test.eventLoop();
        }
        catch(Throwable ex) {
            ex.printStackTrace();
            if(test != null)
                test.stop();
        }
    }

    protected static String csvFilters() {
        Test t=new Test();
        Map<String,Object> m=t.envMap();
        return m.keySet().toString();
    }

    protected static void help() {
        System.out.printf("Test [-factory <cache factory classname>] [-cfg <file>] [-cache <name>] [-control-cfg <file>]\n" +
                            "[-nohup] [-batch-mode true|false] [-nodes <num>] [-warmup <secs>] [-result-file <file>]\n" +
                            "[-threads <num>] [-keys <num>] [-msg-size <bytes>] [-time <secs>]\n" +
                            "[-read-percentage <percentage>] [-use-vthreads true|false] [-csv \"<filter>\"]\n" +
                            "CSV filters: %s\n" +
                            "Valid factory names:" +
                            "\n  ispn: %s\n  hc:   %s\n  coh:  %s\n  tri:  %s\n dummy: %s\n raft: %s\nlocal: %s\n",
                          csvFilters(),
                          infinispan_factory, hazelcast_factory, coherence_factory, tri_factory, dummy_factory, raft_factory, local_factory);
    }


}
