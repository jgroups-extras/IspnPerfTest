package org.cache.impl.tri;

import org.cache.Cache;
import org.jgroups.*;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.stack.DiagnosticsHandler;
import org.jgroups.util.*;
import org.jgroups.util.ThreadFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;


/**
 * Cache which simulates the way Infinispan "triangle" works, but doesn't support rebalancing.<br/>
 * Fixed replication count of 2.<br/>
 * A PUT is sent to the primary which applies the change and forwards it to the backup node. The backup node also applies
 * the change and sends an ACK back to the caller. The caller blocks until it gets the ACK with the value, or a timeout
 * kicks in.<br/>
 * GETs can be returned by both primary and backup owner unless {@link #only_primary_handles_gets} is true.
 * @author Bela Ban
 * @since  1.0
 */
public class TriCache<K,V> implements Receiver, Cache<K,V>, Closeable, Runnable, DiagnosticsHandler.ProbeHandler {
    protected final Map<K,V>                           map=new ConcurrentHashMap<>();
    protected final Log                                log=LogFactory.getLog(TriCache.class);
    protected final boolean                            is_trace;
    protected JChannel                                 ch;
    protected Address                                  local_addr;
    protected volatile Address[]                       members;
    // address of the backup; always the member to our right
    protected volatile Address                         backup;
    protected volatile boolean                         primary_is_backup; // primary == backup (same node)

    // Number of removals in ReqestTable until it is compacted (0 disables this)
    protected int                                      removes_till_compaction=500000;
    protected int                                      put_queue_max_size=1000;
    protected long                                     timeout=30_000; // ms for blocking operations (like get())

    // If true, a GET for key K is handled by the primary owner of K only, otherwise any owner for K can handle a GET(K)
    protected boolean                                  only_primary_handles_gets;

    // Maps req-ids to futures on which callers block (e.g. put() or get()) until an ACK has been received
    protected final RequestTable<CompletableFuture<V>> req_table=new RequestTable<>(128);

    // Queue to handle PUT and CLEAR messages
    protected final BlockingQueue<Data<K,V>>           put_queue;
    protected final LongAdder                          num_msgs_received=new LongAdder();
    protected final Runner                             put_handler;
    protected static final ThreadFactory               thread_factory=new DefaultThreadFactory("tri", false, true)
                                                                             .useVirtualThreads(true);

    static {
        ClassConfigurator.add((short)1500, Data.class);
    }


    public TriCache(String config, String name) throws Exception {
        ch=new JChannel(config).setReceiver(this);
        if(name != null)
            ch.name(name);
        ch.connect("tri");
        this.local_addr=ch.getAddress();
        this.backup=getBackup(local_addr);
        primary_is_backup=Objects.equals(local_addr, backup);
        log.info("I'm %s, backup is %s", local_addr, backup);
        ch.getProtocolStack().getTransport().registerProbeHandler(this);
        req_table.removesTillCompaction(removes_till_compaction);
        put_queue=new ArrayBlockingQueue<>(put_queue_max_size);
        put_handler=new Runner(thread_factory, "put_handler", this, null);
        put_handler.start();
        is_trace=log.isTraceEnabled();
    }


    public TriCache<K,V> removesTillCompaction(int n)      {req_table.removesTillCompaction(this.removes_till_compaction=n); return this;}
    public int           removesTillCompaction()           {return removes_till_compaction;}
    public void          compactRequestTable()             {req_table.compact();}
    public int           putQueueMaxSize()                 {return put_queue_max_size;}
    public TriCache<K,V> putQueueMaxSize(int s)            {put_queue_max_size=s; return this;}
    public boolean       onlyPrimaryHandlesGets()          {return only_primary_handles_gets;}
    public TriCache<K,V> onlyPrimaryHandlesGets(boolean b) {this.only_primary_handles_gets=b; return this;}

    @ManagedAttribute(description="Number of responses currently in the put queue")
    public int           getPutQueueSize()                 {return put_queue.size();}
    public long          timeout()                         {return timeout;}
    public TriCache<K,V> timeout(long t)                   {timeout=t; return this;}


    public void close() throws IOException {
        Util.close(put_handler, ch);
    }

    /**
     * Sends a PUT message to the primary and blocks until an ACK has been received (from the backup node)
     * @param key the new key
     * @param value the new value
     */
    public V put(K key, V value) {
        Address primary=getPrimary(key.hashCode());
        CompletableFuture<V> future=new CompletableFuture<>(); // used to block for response (or timeout)
        long req_id=req_table.add(future);

        try {
            Data<K,V> data=new Data<>(Data.Type.PUT, req_id, key, value, local_addr);
            if(Objects.equals(primary, local_addr)) {
                if(is_trace)
                    log.trace("%s: local put (req=%d key=%s)", local_addr, req_id, key);
                put_queue.add(data);
            }
            else {
                if(is_trace)
                    log.trace("%s: sending put(%s) to primary %s (req=%d)", local_addr, key, primary, req_id);
                send(primary, data);
            }
            return future.get(timeout, TimeUnit.MILLISECONDS); // req_id was removed by ACK processing
        }
        catch(Throwable t) {
            req_table.remove(req_id);                        // req_id is removed on exception
            throw new RuntimeException(t);
        }
    }

    /**
     * Pick the primary member to which key hashes, send a GET request and wait for the get response (ACK)
     * @param key the key
     * @return the value associated with the key, or null if key has not been set
     */
    public V get(K key) {
        Address primary=getPrimary(key.hashCode());
        if(primary == null)
            throw new IllegalArgumentException("primary must not be null");

        boolean get_is_local=Objects.equals(primary, local_addr) ||
          (!only_primary_handles_gets && Objects.equals(getBackup(primary), local_addr));
        if(get_is_local) {
            if(is_trace)
                log.trace("%s: handling get(%s) locally", local_addr, key);
            return map.get(key);
        }

        CompletableFuture<V> future=new CompletableFuture<>(); // used to block for response (or timeout)
        long req_id=req_table.add(future);
        try {
            if(is_trace)
                log.trace("%s: sending get(%s) to %s (req=%d)", local_addr, key, primary, req_id);
            Data<K,V> data=new Data<>(Data.Type.GET, req_id, key, null, null);
            send(primary, data);
            return future.get(timeout, TimeUnit.MILLISECONDS);  // req_id was removed by ACK processing
        }
        catch(Exception t) {                                  // req_id is removed on exception
            req_table.remove(req_id);
            throw new RuntimeException(t);
        }
    }

    public void clear() {
        Data<K,V> data=new Data<>(Data.Type.CLEAR, 0, null, null, null);
        try {
            send(null, data);
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public Set<K> keySet() {
        return map.keySet();
    }

    public Map<K,V> getContents() {
        return map;
    }

    public void receive(Message msg) {
        try {
            Data<K,V> data=msg.getObject();
            process(data, msg.src());
        }
        catch(Exception t) {
            throw new RuntimeException(t);
        }
    }

    public void receive(MessageBatch batch) {
        try {
            for(Message msg: batch)
                receive(msg);
        }
        catch(Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public void viewAccepted(View view) {
        System.out.printf("-- view: %s\n", view);
        members=view.getMembersRaw();
        if(local_addr != null) {
            backup=getBackup(local_addr);
            primary_is_backup=Objects.equals(local_addr, backup);
            log.info("I'm %s, backup is %s (primary %s backup)", local_addr, backup, primary_is_backup? "==" : "!=");
        }
    }

    public Map<String,String> handleProbe(String... keys) {
        Map<String,String> m=new HashMap<>();
        for(String key: keys) {
            switch(key) {
                case "tri":
                    m.put("tri.req_table", req_table.toString());
                    m.put("tri.num_msgs_received", String.format("%,d", num_msgs_received.sum()));
                    m.put("tri.put_queue_size", String.format("%,d", put_queue.size()));
                    break;
                case "tri.compact":
                    boolean result=req_table.compact();
                    m.put("compact", String.valueOf(result));
                    break;
                case "tri.reset":
                    num_msgs_received.reset();
                    break;
            }
        }
        return m;
    }

    public String[] supportedKeys() {
        return new String[]{"tri", "tri.compact", "tri.reset"};
    }

    public void run() {
        try {
            Data<K,V> data=put_queue.take();
            handlePut(data);
        }
        catch(InterruptedException e) {
        }
    }

    /**
     * Locks the cache, applies the change, sends a BACKUP message to the backup node asynchronously, unlocks the cache
     * and returns. All PUTs are invoked sequentially, so we don't need locks.
     */
    protected void handlePut(Data<K,V> data) {
        // reuse data instead of creating a new instance
        data.type=Data.Type.BACKUP;

        String m=is_trace?
          String.format("%s: received put(%s) from %s (req=%d)", local_addr, data.key, data.sender, data.req_id) : null;

        map.put(data.key, data.value);
        // System.out.printf("put(%s,%d)\n", data.key, Bits.readLong((byte[])data.value, Global.LONG_SIZE*2));

        if(primary_is_backup) { // primary == backup (e.g. when cluster size is 1: ack directly
            data.type=Data.Type.ACK;
            handleAck(data);
        }
        else {
            if(m != null)
                log.trace("%s, sending backup(%s) to %S", m, data.key, backup);
            sendData(backup, data, false);
        }
    }

    /**
     * Applies a BACKUP. No locking or ordering is needed as updates for the same key always come from the same primary,
     * which sends messages to the backup (us) in FIFO (sender) order anyway
     */
    protected void handleBackup(Data<K,V> data, Address sender) {
        map.put(data.key, data.value);

        if(is_trace)
            log.trace("%s: received backup(%s) from %s (req=%d)", local_addr, data.key, sender, data.req_id);

        K key=data.key;
        data.type=Data.Type.ACK; // reuse data again
        data.key=null;
        // As we're comparing against Infinispan's Cache.withFlags(Flag.IGNORE_RETURN_VALUES); and Hazelcast's set():
        data.value=null;
        Address dest=data.sender;
        data.sender=null;
        if(Objects.equals(local_addr, dest)) {
            if(is_trace)
                log.trace("%s: handling ack(%s) locally (req=%d)", local_addr, key, data.req_id);
            handleAck(data);
        }
        else {
            if(is_trace)
                log.trace("%s: sending ack(%s) to original requester %s (req=%d)", local_addr, key, dest, data.req_id);
            sendData(dest, data, false);
        }
    }

    protected void handleAck(Data<K,V> data) {
        CompletableFuture<V> future=req_table.remove(data.req_id);
        if(future != null) {
            future.complete(data.value);
            if(is_trace)
                log.trace("%s: ack (req=%d)", local_addr, data.req_id);
        }
    }

    protected void handleClear() {
        map.clear();
    }

    protected void process(Data<K,V> data, Address sender) throws Exception {
        num_msgs_received.increment();
        switch(data.type) {
            case PUT:
                put_queue.add(data.sender(sender));
                break;
            case GET:
                if(is_trace)
                    log.trace("%s: received get(%s) from %s (req=%d), sending response", local_addr, data.key, sender, data.req_id);
                data.type=Data.Type.ACK; // reuse data
                K key=data.key;
                data.value=map.get(key);
                sendData(sender, data, true);
                break;
            case CLEAR:
                handleClear();
                break;
            case BACKUP:
                handleBackup(data, sender);
                break;
            case ACK:
                handleAck(data);
                break;
            default:
                throw new IllegalArgumentException(String.format("type %s not known", data.type));
        }
    }

    protected Message createMessage(Address dest, Data<K,V> data, boolean oob) throws Exception {
        Message msg=new ObjectMessage(dest, data);
        if(oob)
            msg.setFlag(Message.Flag.OOB);
        return msg;
    }

    protected void send(Address dest, Data<K,V> data) throws Exception {
        ch.send(createMessage(dest, data, false));
    }

    protected void sendData(Address dest, Data<K,V> data, boolean oob) {
        try {
            Message msg=createMessage(dest, data, oob);
            ch.send(msg);
        }
        catch(Throwable t) {
            log.error("%s: failed sending data to %s: %s", local_addr, data.sender, t);
        }
    }

    protected Address getPrimary(int hash) {
        Address[] mbrs=this.members;
        int index=hash % mbrs.length;
        return mbrs[index];
    }

    protected Address getBackup(Address primary) {
        Address[] mbrs=this.members;
        for(int i=0; i < mbrs.length; i++) {
            if(Objects.equals(mbrs[i], primary))
                return mbrs[(i+1) % mbrs.length];
        }
        return null;
    }

    // simple method to compute sizes of keys and values. we know we use Integer as keys and byte[] as values
    protected static int estimatedSizeOf(Object obj) {
        if(obj == null)
            return 1;
        if(obj instanceof Integer)
            return Global.INT_SIZE +1;
        if(obj instanceof byte[])
            return ((byte[])obj).length + 1 + Global.INT_SIZE*2; // byte[], offset, length
        if(obj instanceof String)
            return ((String)obj).length() *2 +4;
        return 255;
    }

}
