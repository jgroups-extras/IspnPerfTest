package org.perf;

import org.cache.Cache;
import org.cache.CacheFactory;
import org.cache.impl.*;
import org.cache.impl.tri.TriCacheFactory;
import org.infinispan.commons.jdkspecific.ThreadCreator;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.*;
import org.jgroups.annotations.Property;
import org.jgroups.protocols.TP;
import org.jgroups.util.UUID;
import org.jgroups.util.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutorService;


/**
 * Simple demo, doing interactive puts and gets. Mostly used for debugging; to see which path a GET or PUT/BACKUP takes
 * @author Bela Ban
 */
public class Demo implements Receiver {
    protected CacheFactory<Integer,byte[]>        cache_factory;
    protected Cache<Integer,byte[]>               cache;
    protected JChannel                            control_channel;
    protected String                              cfg="dist-sync.xml", control_cfg="control.xml";
    protected Address                             local_addr;
    protected final List<Address>                 members=new ArrayList<>();
    protected volatile View                       view;
    protected volatile boolean                    looping=true;
    protected Integer[]                           keys;
    protected final Promise<Config>               config_promise=new Promise<>();
    protected ThreadFactory                       thread_factory;

    protected enum Type {
        GET_CONFIG_REQ,
        GET_CONFIG_RSP,       // Config
        SET,                  // field-name (String), value (Object)
        QUIT_ALL
    }

    // ============ configurable properties ==================
    @Property protected int     msg_size=1000;
    // =======================================================

    protected static final String infinispan_factory=InfinispanCacheFactory.class.getName();
    protected static final String hazelcast_factory=HazelcastCacheFactory.class.getName();
    protected static final String coherence_factory="org.cache.impl.CoherenceCacheFactory"; // to prevent loading of Coherence up-front
    protected static final String tri_factory=TriCacheFactory.class.getName();
    protected static final String dummy_factory=DummyCacheFactory.class.getName();
    protected static final String raft_factory=RaftCacheFactory.class.getName();
    protected static final String local_factory=LocalCacheFactory.class.getName();

    protected static final String input_str="[1] Get [2] Put [3] View [4] Cache size [5] Populate\n" +
      "[7] Value size (%s) \n[v] Versions\n" +
      "[x] Exit [X] Exit all\n";

    public Demo cfg(String c)            {this.cfg=c; return this;}
    public Demo controlCfg(String c)     {this.control_cfg=c; return this;}
    public Demo msgSize(int s)           {this.msg_size=s; return this;}

    public void init(String factory, String cache_name, boolean use_vthreads) throws Exception {
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
    }

    protected void stop() {
        Util.close(control_channel);
        if(cache_factory != null)
            cache_factory.destroy();
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
            case QUIT_ALL:
                quitAll();
                break;
            default:
                throw new IllegalArgumentException(String.format("type %s not known", type));
        }
    }

    public void viewAccepted(View new_view) {
        this.view=new_view;
        members.clear();
        members.addAll(new_view.getMembers());
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
    }

    protected Config getConfig() {
        Config config=new Config();
        for(Field field: Util.getAllDeclaredFieldsWithAnnotations(Demo.class, Property.class))
            config.add(field.getName(), Util.getField(field, this));
        return config;
    }

    protected void applyConfig(Config config) {
        for(Map.Entry<String,Object> entry : config.values.entrySet()) {
            Field field=Util.getField(getClass(), entry.getKey());
            Util.setField(field, this, entry.getValue());
        }
    }

    // ================================= end of callbacks =====================================

    public void eventLoop() throws Throwable {
        while(looping) {
            int c=Util.keyPress(String.format(input_str, Util.printBytes(msg_size)));
            switch(c) {
                case '1': // get
                    int key=Util.readIntFromStdin("key: ");
                    byte[] val=cache.get(key);
                    System.out.printf("val: " + (val == null? "null\n" : (val.length + " bytes\n")));
                    break;
                case '2': // put
                    key=Util.readIntFromStdin("key: ");
                    cache.put(key, new byte[msg_size]);
                    break;
                case '3':
                    printView();
                    break;
                case '4':
                    printCacheSize();
                    break;
                case '5':
                    int highest_key=Util.readIntFromStdin("highest key: ");
                    byte[] v=new byte[msg_size];
                    for(int k=1; k <= highest_key; k++)
                        cache.put(k, v);
                    break;
                case '7':
                    int new_msg_size=Util.readIntFromStdin("Message size: ");
                    if(new_msg_size < Global.LONG_SIZE*3)
                        System.err.println("msg size must be >= " + Global.LONG_SIZE*3);
                    else
                        changeFieldAcrossCluster("msg_size", new_msg_size);
                    break;
                case 'c':
                    clearCache();
                    break;
                case 'v':
                    System.out.printf("JGroups: %s, Infinispan: %s\n",
                                      Version.printDescription(),
                                      org.infinispan.commons.util.Version.printVersion());
                    break;
                case 'x':
                case 0: // remove on upgrade to next JGroups version
                case -1:
                    stop();
                    return;
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

    protected void printCacheSize() {
        int size=cache.size();
        System.out.println("-- cache has " + size + " elements");
    }

    protected void clearCache() {
        cache.clear();
    }

    protected static void writeTo(UUID addr, long seqno, byte[] buf, int offset) {
        long low=addr.getLeastSignificantBits(), high=addr.getMostSignificantBits();
        Bits.writeLong(low, buf, offset);
        Bits.writeLong(high, buf, offset + Global.LONG_SIZE);
        Bits.writeLong(seqno, buf, offset + Global.LONG_SIZE *2);
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
        boolean use_vthreads=true;

        Demo test=new Demo();
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
            if("-control-cfg".equals(args[i])) {
                test.controlCfg(args[++i]);
                continue;
            }
            if("-use-virtual-threads".equals(args[i]) || "-use-vthreads".equals(args[i])) {
                use_vthreads=Boolean.parseBoolean(args[++i]);
                continue;
            }
            if("-msg-size".equals(args[i])) {
                test.msgSize(Integer.parseInt(args[++i]));
                continue;
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
            test.eventLoop();
        }
        catch(Throwable ex) {
            ex.printStackTrace();
            if(test != null)
                test.stop();
        }
    }

    protected static void help() {
        System.out.printf("Test [-factory <cache factory classname>] [-cfg <file>] [-cache <name>] [-control-cfg <file>]\n" +
                            "[-msg-size <bytes>] [-use-vthreads true|false]\n" +
                            "Valid factory names:" +
                            "\n  ispn: %s\n  hc:   %s\n  coh:  %s\n  tri:  %s\n dummy: %s\n raft: %s\nlocal: %s\n",
                          infinispan_factory, hazelcast_factory, coherence_factory, tri_factory, dummy_factory, raft_factory, local_factory);
    }


}
