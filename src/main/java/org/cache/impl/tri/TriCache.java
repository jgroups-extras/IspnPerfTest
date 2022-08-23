package org.cache.impl.tri;

import org.cache.Cache;
import org.jgroups.*;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.stack.DiagnosticsHandler;
import org.jgroups.util.*;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;


/**
 * Cache which simulates the way Infinispan "triangle" works, but doesn't support rehashing. Fixed replication count of 2.
 * A PUT is sent to the primary which applies the change and forwards it to the backup node. The backup node also applies
 * the change and sends an ACK back to the caller. The caller blocks until it gets the ACK with the value, or a timeout
 * kicks in.
 * @author Bela Ban
 * @since  1.0
 */
public class TriCache<K,V> implements Receiver, Cache<K,V>, Closeable, DiagnosticsHandler.ProbeHandler {
    protected final Map<K,V>                           map=new ConcurrentHashMap<>();
    protected final Log                                log=LogFactory.getLog(TriCache.class);
    protected JChannel                                 ch;
    protected Address                                  local_addr;
    protected volatile Address[]                       members;
    // address of the backup; always the member to our right
    protected volatile Address                         backup;
    protected volatile boolean                         primary_is_backup; // primary == backup (same node)

    // Number of removals in ReqestTable until it is compacted (0 disables this)
    protected int                                      removes_till_compaction=500000;

    protected int                                      put_queue_max_size=1000;

    protected int                                      ack_queue_max_size=1000;

    // If true, a GET for key K is handled by the primary owner of K only, otherwise any owner for K can handle a GET(K)
    protected boolean                                  only_primary_handles_gets;

    protected boolean                                  stats=true;


    // Maps req-ids to futures on which callers block (e.g. put() or get()) until an ACK has been received
    protected final RequestTable<CompletableFuture<V>> req_table=new RequestTable<>(128);

    // Queue to handle PUT and CLEAR messages
    protected final ProcessingQueue                    put_queue;

    // Queue to handle ACKs
    protected final ProcessingQueue                    ack_queue;


    protected final LongAdder                          num_single_msgs_received=new LongAdder();
    protected final LongAdder                          num_data_batches_received=new LongAdder();
    protected final AverageMinMax                      avg_batch_size=new AverageMinMax();
    protected final AverageMinMax                      avg_batch_processing_time=new AverageMinMax();
    protected final AverageMinMax                      avg_put_processing_time=new AverageMinMax();
    protected final AverageMinMax                      avg_ack_processing_time=new AverageMinMax();


    protected final RejectedExecutionHandler resubmit_handler=(r,tp) -> {
        try {
            tp.getQueue().put(r); // blocks until element can be added to the queue
        }
        catch(InterruptedException e) {
            log.error("resubmitting the runnable %s failed: %s", r, e);
        }
    };



    public TriCache(String config) throws Exception {
        ch=new JChannel(config);
        ch.setReceiver(this);
        ch.connect("tri");
        this.local_addr=ch.getAddress();
        this.backup=getBackup(local_addr);
        primary_is_backup=Objects.equals(local_addr, backup);
        log.info("I'm %s, backup is %s (primary %s backup)\n", local_addr, backup, primary_is_backup? "==" : "!=");
        ch.getProtocolStack().getTransport().registerProbeHandler(this);
        req_table.removesTillCompaction(removes_till_compaction);
        put_queue=new ProcessingQueue(put_queue_max_size, 1, 30000, "put-queue-handler", resubmit_handler);
        ack_queue=new ProcessingQueue(ack_queue_max_size, 10, 30000, "ack-queue-handler", resubmit_handler);
    }


    public TriCache<K,V> removesTillCompaction(int n)      {req_table.removesTillCompaction(this.removes_till_compaction=n); return this;}
    public int           removesTillCompaction()           {return removes_till_compaction;}
    public void          compactRequestTable()             {req_table.compact();}
    public int           putQueueMaxSize()                 {return put_queue_max_size;}
    public TriCache<K,V> putQueueMaxSize(int s)            {put_queue_max_size=s; return this;}
    public int           ackQueueMaxSize()                 {return ack_queue_max_size;}
    public TriCache<K,V> ackQueueMaxSize(int s)            {this.ack_queue_max_size=s; return this;}
    public boolean       onlyPrimaryHandlesGets()          {return only_primary_handles_gets;}
    public TriCache<K,V> onlyPrimaryHandlesGets(boolean b) {this.only_primary_handles_gets=b; return this;}

    @ManagedAttribute(description="Number of responses currently in the put queue")
    public int           getPutQueueSize()                 {return put_queue.size();}

    @ManagedAttribute(description="Number of responses currently in the ack queue")
    public int           getAckQueueSize()                 {return ack_queue.size();}


    public void close() throws IOException {
        Util.close(put_queue, ack_queue, ch);
    }

    /**
     * Sends a PUT message to the primary and blocks until an ACK has been received (from the backup node)
     * @param key the new key
     * @param value the new value
     */
    public V put(K key, V value) {
        int hash=hash(key);
        Address primary=getPrimary(hash);
        CompletableFuture<V> future=new CompletableFuture<>(); // used to block for response (or timeout)
        long req_id=req_table.add(future);

        try {
            Data<K,V> data=new Data<>(Data.Type.PUT, req_id, key, value, local_addr);
            if(Objects.equals(primary, local_addr))
                put_queue.add(data.handler(this::handlePut));
            else
                send(primary, data);
            return future.get(10000, TimeUnit.MILLISECONDS); // req_id was removed by ACK processing
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
        Address primary=getPrimary(hash(key));
        if(primary == null)
            throw new IllegalArgumentException("primary must not be null");

        boolean get_is_local=Objects.equals(primary, local_addr) ||
          (!only_primary_handles_gets && Objects.equals(getBackup(primary), local_addr));
        if(get_is_local)
            return map.get(key);

        CompletableFuture<V> future=new CompletableFuture<>(); // used to block for response (or timeout)
        long req_id=req_table.add(future);

        try {
            Data<K,V> data=new Data<>(Data.Type.GET, req_id, key, null, null);
            send(primary, data, false); // sent as regular message
            return future.get(10000, TimeUnit.MILLISECONDS);  // req_id was removed by ACK processing
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
        ByteArrayDataInputStream in=new ByteArrayDataInputStream(msg.getArray(), msg.getOffset(), msg.getLength());
        try {
            int num_elements=Bits.readIntCompressed(in);
            if(num_elements == 1) {
                Data<K,V> data=(Data<K,V>)new Data<>().read(in);
                process(data, msg.src());
            }
            else {
                Address sender=msg.src();
                DataBatch<K,V> batch=new DataBatch<>(sender, num_elements);
                for(int i=0; i < num_elements; i++)
                    batch.add(new Data<K,V>().read(in));
                avg_batch_size.add(num_elements);
                process(batch);
            }
        }
        catch(Exception t) {
            throw new RuntimeException(t);
        }
    }

    public void receive(MessageBatch batch) {
        int max_elements=countData(batch);
        DataBatch data_batch=new DataBatch(batch.sender(), max_elements);
        try {
            for(Message msg : batch) {
                ByteArrayDataInputStream in=new ByteArrayDataInputStream(msg.getArray(), msg.getOffset(), msg.getLength());
                int num=Bits.readIntCompressed(in);
                for(int i=0; i < num; i++)
                    data_batch.add(new Data<>().read(in));
            }
            avg_batch_size.add(max_elements);
            process(data_batch);
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
            log.info("I'm %s, backup is %s (primary %s backup)\n", local_addr, backup, primary_is_backup? "==" : "!=");
        }
    }

    public Map<String,String> handleProbe(String... keys) {
        Map<String,String> m=new HashMap<>();
        for(String key: keys) {
            switch(key) {
                case "tri":
                    m.put("tri.req-table", req_table.toString());
                    m.put("tri.avg_batch_size", avg_batch_size.toString());
                    if(stats) {
                        m.put("tri.avg_batch_processing_time", avg_batch_processing_time.toString() + " us");
                        m.put("tri.avg_batch_put_processing_time", avg_put_processing_time.toString() + " us");
                        m.put("tri.avg_ack_processing_time", avg_ack_processing_time.toString() + " us");
                    }
                    m.put("tri.num_single_msgs_received", String.valueOf(num_single_msgs_received.sum()));
                    m.put("tri.num_data_batches_received", String.valueOf(num_data_batches_received.sum()));
                    m.put("tri.put-queue", put_queue.toString());
                    m.put("tri.ack-queue", ack_queue.toString());
                    break;
                case "tri.compact":
                    boolean result=req_table.compact();
                    m.put("compact", String.valueOf(result));
                    break;
                case "tri.reset":
                    Stream.of(avg_batch_size, avg_batch_processing_time, avg_put_processing_time, avg_ack_processing_time)
                      .forEach(AverageMinMax::clear);
                    Stream.of(num_single_msgs_received, num_data_batches_received)
                      .forEach(LongAdder::reset);
                    break;
            }
        }

        return m;
    }

    public String[] supportedKeys() {
        return new String[]{"tri", "tri.compact", "tri.reset"};
    }

    /**
     * Locks the cache, applies the change, sends a BACKUP message to the backup node asynchronously, unlocks the cache
     * and returns. All PUTs are invoked sequentially, so we don't need locks.
     */
    protected void handlePut(Data<K,V> data) {
        // reuse data instead of creating a new instance
        data.type=Data.Type.BACKUP;

        map.put((K)data.key, (V)data.value);
        // System.out.printf("put(%s,%d)\n", data.key, Bits.readLong((byte[])data.value, Global.LONG_SIZE*2));

        if(primary_is_backup) { // primary == backup (e.g. when cluster size is 1: ack directly
            data.type=Data.Type.ACK;
            handleAck(data);
        }
        else
            sendData(backup, data, false);
    }

    /** Handles all PUTs and CLEARs. Processing is sequential, this method will never be called concurrently
     * (with itself or handlePut()). This is needed to ensure PUT handling and sending of BACKUP message atomically */
    protected void handlePutBatch(DataBatch<K,V> batch) {
        long start=stats? Util.micros() : 0;
        for(int i=0; i < batch.pos; i++) {
            Data<K,V> data=batch.data[i];
            if(data == null)
                continue;
            switch(data.type) {

                // replace PUT with BACKUP in place so the batch cab be reused for sending
                case PUT:
                    // reuse data instead of creating a new instance
                    data.sender=batch.addr;
                    map.put((K)data.key, (V)data.value);
                    // System.out.printf("put(%s,%d)\n", data.key, Bits.readLong((byte[])data.value, Global.LONG_SIZE*2));

                    if(primary_is_backup) { // primary == backup (e.g. when cluster size is 1: ack directly
                        data.type=Data.Type.ACK;
                        handleAck(data);
                        batch.data[i]=null;
                    }
                    else
                        data.type=Data.Type.BACKUP;
                    break;

                // handle CLEAR and null the element in the batch
                case CLEAR:
                    handleClear();
                    batch.data[i]=null; // null ACKs
                    break;
            }
        }
        if(!primary_is_backup && !batch.isEmpty()) {
            // PUT and BACKUP needs to be done on the same thread; that's why we cannot add the batch to the send queue
            try {
                send(backup, batch, Data.Type.BACKUP, false);
            }
            catch(Exception e) {
                log.error("failed sending batch of %d BACKUPs to %s: %s", batch.size(Data.Type.BACKUP), backup, e);
            }
        }
        if(stats)
            avg_put_processing_time.add(Util.micros()-start);
    }

    // processes all ACK_DELAYED types (changed from ACK before)
    protected void handleAckBatch(DataBatch<K,V> batch) {
        long start=stats? Util.micros() : 0;
        for(int i=0; i < batch.pos; i++) {
            Data<K,V> data=batch.data[i];
            if(data == null)
                continue;
            if(data.type == Data.Type.ACK_DELAYED)
                handleAck(data);
        }
        if(stats)
            avg_ack_processing_time.add(Util.micros()-start);
    }


    /**
     * Applies a BACKUP. No locking or ordering is needed as updates for the same key always come from the same primary,
     * which sends messages to the backup (us) in FIFO (sender) order anyway
     */
    protected void handleBackup(Data<K,V> data) {
        map.put((K)data.key, (V)data.value);
        // System.out.printf("backup(%s,%d)\n", data.key, Bits.readLong((byte[])data.value, Global.LONG_SIZE*2));

        data.type=Data.Type.ACK; // reuse data again
        data.key=null;
        // As we're comparing against Infinispan's Cache.withFlags(Flag.IGNORE_RETURN_VALUES); and Hazelcast's set():
        data.value=null;
        Address dest=data.sender;
        data.sender=null;
        if(Objects.equals(local_addr, dest))
            handleAck(data);
        else
            sendData(dest, data, false);
    }


    protected void handleAck(Data<K,V> data) {
        CompletableFuture<V> future=req_table.remove(data.req_id);
        if(future != null)
            future.complete((V)data.value);
    }

    protected void handleClear() {
        map.clear();
    }

    protected int hash(K key) {
        return key.hashCode();
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



    protected void process(Data<K,V> data, Address sender) throws Exception {
        num_single_msgs_received.increment();
        switch(data.type) {
            case PUT:
                put_queue.add(data.sender(sender).handler(this::handlePut));
                break;
            case GET:
                data.type=Data.Type.ACK; // reuse data
                K key=data.key;
                data.value=map.get(key);
                sendData(sender, data, true);
                break;
            case CLEAR:
                handleClear();
                break;
            case BACKUP:
                handleBackup(data);
                break;
            case ACK:
                handleAck(data);
                break;
            default:
                throw new IllegalArgumentException(String.format("type %s not known", data.type));
        }
    }


    protected void process(DataBatch<K,V> batch) {
        int puts=0, gets=0, acks=0;
        num_data_batches_received.increment();
        long start=stats? Util.micros() : 0;

        for(int i=0; i < batch.pos; i++) {
            Data<K,V> data=batch.data[i];
            if(data == null)
                continue;
            switch(data.type) {
                case PUT:
                case CLEAR:
                    puts++;
                    break;

                // replace GET with an ACK (return value) *in-place* so the batch can be reused for sending
                case GET:
                    K key=(K)data.key;
                    data.value=map.get(key);
                    data.type=Data.Type.ACK; // reuse batch
                    gets++;
                    break;

                // send an ACK to the original sender and null the element in the batch
                case BACKUP:
                    handleBackup(data);
                    batch.data[i]=null;
                    break;

                // release the blocker requester (of a PUT or GET) and null the element in the batch
                case ACK:
                    acks++;
                    data.type=Data.Type.ACK_DELAYED; // to prevent async GET handling from re-sending the received ACK
                    break;
                default:
                    throw new IllegalArgumentException(String.format("type %s not known", data.type));
            }
        }

        if(puts > 0)
            put_queue.add(batch.handler(db -> handlePutBatch((DataBatch<K,V>)db)));

        if(acks > 0)
            ack_queue.add(batch.handler(db -> handleAckBatch((DataBatch<K,V>)db)));

        if(gets > 0) {
            try {
                send(batch.addr, batch, Data.Type.ACK, true); // only send the ACKs (GET responses) to the sender of the batch
            }
            catch(Exception e) {
                log.error("failed sending batch of %d ACKs to %s: %s", gets, batch.addr, e);
            }
        }
        if(stats)
            avg_batch_processing_time.add(Util.micros()-start);
    }



    /**
     * Counts the Data items in a batch. Note that each message in the batch can have a single or multiple Data items!
     */
    protected static int countData(MessageBatch batch) {
        int count=0;
        for(Message msg: batch) {
            byte[] buf=msg.getArray();
            if(buf != null) {
                int num=Bits.readIntCompressed(buf, msg.getOffset());
                count+=num;
            }
        }
        return count;
    }

    protected Message createMessage(Address dest, Data<K,V> data, boolean oob) throws Exception {
        int expected_size=Global.INT_SIZE + data.serializedSize();
        ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(expected_size);
        Bits.writeIntCompressed(1, out);
        data.writeTo(out);
        Message msg=new BytesMessage(dest, out.buffer(), 0, out.position());
        if(oob)
            msg.setFlag(Message.Flag.OOB);
        return msg;
    }



    protected static <K,V> Message createMessage(Address dest, DataBatch<K,V> data, boolean oob) throws Exception {
        ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(data.serializedSize());
        data.writeTo(out);
        Message msg=new BytesMessage(dest, out.buffer(), 0, out.position());
        if(oob)
            msg.setFlag(Message.Flag.OOB);
        return msg;
    }

    protected static <K,V> Message createMessage(Address dest, DataBatch<K,V> data, Data.Type type, boolean oob) throws Exception {
        ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(data.serializedSize(type));
        data.writeTo(out, type);
        Message msg=new BytesMessage(dest, out.buffer(), 0, out.position());
        if(oob)
            msg.setFlag(Message.Flag.OOB);
        return msg;
    }



    protected void send(Address dest, Data<K,V> data) throws Exception {
        ch.send(createMessage(dest, data, false));
    }

    protected void send(Address dest, Data<K,V> data, boolean oob) throws Exception {
        ch.send(createMessage(dest, data, oob));
    }


    protected void send(Address dest, DataBatch<K,V> batch, Data.Type type, boolean oob) throws Exception {
        ch.send(createMessage(dest, batch, type, oob));
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





    protected static class ProcessingQueue implements Closeable {
        protected final BlockingQueue<Runnable> queue;
        protected final Executor                thread_pool;
        protected volatile boolean              running=true;


        protected ProcessingQueue() {
            thread_pool=new DirectExecutor();
            queue=null;
        }

        protected ProcessingQueue(int queue_capacity, int max_threads, long keep_alive_millis,
                                  String base_name, RejectedExecutionHandler rejection_handler) {
            queue=new ArrayBlockingQueue<>(queue_capacity);

            // min == max, but core threads can time out: creates up to max_threads first, then queues
            thread_pool=new ThreadPoolExecutor(max_threads, max_threads, keep_alive_millis, TimeUnit.MILLISECONDS,
                                               queue, new DefaultThreadFactory(base_name, false, true),
                                               rejection_handler);
            ((ThreadPoolExecutor)thread_pool).allowCoreThreadTimeOut(true);
        }

        protected ProcessingQueue         add(Runnable r)  {thread_pool.execute(r); return this;}
        protected BlockingQueue<Runnable> queue()          {return queue;}
        protected boolean                 isRunning()      {return running;}
        protected int                     size()           {return queue != null? queue.size() : 0;}

        public void close() {
            if(thread_pool instanceof ExecutorService)
                ((ExecutorService)thread_pool).shutdown();
        }

        public String toString() {
            if(thread_pool instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor p=(ThreadPoolExecutor)thread_pool;
                return String.format("[pool=%d, largest pool=%d, active=%d, queued tasks=%d, completed tasks=%d]",
                                     p.getPoolSize(), p.getLargestPoolSize(), p.getActiveCount(),
                                     p.getQueue().size(), p.getCompletedTaskCount());
            }
            return thread_pool.toString();
        }
    }

}
