package org.cache.impl;

import org.cache.Cache;
import org.cache.impl.tri.Data;
import org.jgroups.*;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;



/**
 *This cache doesn't store keys and values, but gets only return a prefabricated byte[] array and puts send a
 * prefabricated byte[] array but don't store it.<br/>
 * This should be the perf baseline for InfinispanCache and TriCache.<br/>
 * Formats:
 * <pre>
 *     GET:    | type (short) | req-id (long) |
 *     ACK:    | type (short) | req-id (long) | buffer (SIZE)
 *     PUT:    | type (short) | req-id (long) | buffer (SIZE)
 *     BACKUP: | type (short) | req-id (long) | fwd-addr (long,long (UUID)) | buffer (SIZE)
 * </pre>
 * @author Bela Ban
 * @since  1.0
 */
public class DummyCache<K,V> implements Receiver, Cache<K,V>, Closeable, DiagnosticsHandler.ProbeHandler {
    protected final Log                                log=LogFactory.getLog(DummyCache.class);
    protected JChannel                                 ch;
    protected Address                                  local_addr;
    protected volatile Address[]                       members;
    // address of the backup; always the member to our right
    protected volatile Address                         backup;
    protected volatile boolean                         primary_is_backup; // primary == backup (same node)

    // Number of removals in ReqestTable until it is compacted (0 disables this)
    protected int                                      removes_till_compaction=500000;

    // If true, a GET for key K is handled by the primary owner of K only, otherwise any owner for K can handle a GET(K)
    protected boolean                                  only_primary_handles_gets;

    protected boolean                                  stats=true;


    // Maps req-ids to futures on which callers block (e.g. put() or get()) until an ACK has been received
    protected final RequestTable<CompletableFuture<V>> req_table=new RequestTable<>(128);
    protected static final int                         SIZE=1000;

    protected final LongAdder                          num_single_msgs_received=new LongAdder();
    protected final AverageMinMax                      avg_batch_processing_time=new AverageMinMax();
    protected final AverageMinMax                      avg_put_processing_time=new AverageMinMax();

    protected static final byte[]                      CLEAR_PAYLOAD={(byte)Data.Type.CLEAR.ordinal()};
    protected static final byte[]                      ACK_PAYLOAD=new byte[SIZE];



    public DummyCache(String config) throws Exception {
        ch=new JChannel(config);
        ch.setReceiver(this);
        ch.connect("dummy");
        this.local_addr=ch.getAddress();
        this.backup=getBackup(local_addr);
        primary_is_backup=Objects.equals(local_addr, backup);
        log.info("I'm %s, backup is %s (primary %s backup)\n", local_addr, backup, primary_is_backup? "==" : "!=");
        ch.getProtocolStack().getTransport().registerProbeHandler(this);
        req_table.removesTillCompaction(removes_till_compaction);
    }


    public DummyCache<K,V> removesTillCompaction(int n)      {req_table.removesTillCompaction(this.removes_till_compaction=n); return this;}
    public int             removesTillCompaction()           {return removes_till_compaction;}
    public void            compactRequestTable()             {req_table.compact();}
    public boolean         onlyPrimaryHandlesGets()          {return only_primary_handles_gets;}
    public DummyCache<K,V> onlyPrimaryHandlesGets(boolean b) {this.only_primary_handles_gets=b; return this;}




    public void close() throws IOException {
        Util.close(ch);
    }

    /**
     * Sends a PUT message to the primary and blocks until an ACK has been received (from the backup node)
     * @param key the new key
     * @param value the new value
     */
    public V put(K key, V value) {
        int hash=key.hashCode();
        Address primary=getPrimary(hash);
        CompletableFuture<V> future=new CompletableFuture<>(); // used to block for response (or timeout)
        long req_id=req_table.add(future);

        try {
            if(Objects.equals(primary, local_addr))
                ; // local put
            else {
                byte[] put_payload=new byte[Global.BYTE_SIZE + Global.LONG_SIZE + SIZE];
                put_payload[0]=(byte)Data.Type.PUT.ordinal();
                Bits.writeLong(req_id, put_payload, 1);
                send(primary, put_payload);
            }
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
        Address primary=getPrimary(key.hashCode());
        if(primary == null)
            throw new IllegalArgumentException("primary must not be null");

        boolean get_is_local=Objects.equals(primary, local_addr) ||
          (!only_primary_handles_gets && Objects.equals(getBackup(primary), local_addr));
        if(get_is_local)
            return (V)ACK_PAYLOAD;

        CompletableFuture<V> future=new CompletableFuture<>(); // used to block for response (or timeout)
        long req_id=req_table.add(future);

        try {
            byte[] get_payload=new byte[Global.BYTE_SIZE + Global.LONG_SIZE];
            get_payload[0]=(byte)Data.Type.GET.ordinal();
            Bits.writeLong(req_id, get_payload, 1);
            Message msg=new BytesMessage(primary, get_payload);
            ch.send(msg);
            return future.get(10000, TimeUnit.MILLISECONDS);  // req_id was removed by ACK processing
        }
        catch(Exception t) {                                  // req_id is removed on exception
            req_table.remove(req_id);
            throw new RuntimeException(t);
        }
    }

    public void clear() {
        Message msg=new BytesMessage(null, CLEAR_PAYLOAD);
        try {
            ch.send(msg);
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int size() {
        return 0;
    }

    public boolean isEmpty() {
        return true;
    }

    public Set<K> keySet() {
        return null;
    }

    public Map<K,V> getContents() {
        return null;
    }

    public void receive(Message msg) {
        byte[] buf=msg.getArray();

        byte tmp=buf[0];
        Data.Type type=Data.Type.values()[tmp];
        switch(type) {
            case GET:
                long req_id=Bits.readLong(buf, 1);
                byte[] ack_payload=new byte[SIZE + Global.BYTE_SIZE + Global.LONG_SIZE];
                ack_payload[0]=(byte)Data.Type.ACK.ordinal();
                Bits.writeLong(req_id, ack_payload, 1);
                send(msg.src(), ack_payload);
                break;
            case ACK:
                req_id=Bits.readLong(buf, 1);
                handleAck(req_id);
                break;
            case PUT:
                req_id=Bits.readLong(buf, 1);
                if(primary_is_backup) { // primary == backup (e.g. when cluster size is 1: ack directly
                    handleAck(req_id);
                }
                else {
                    byte[] backup_payload=new byte[Global.BYTE_SIZE + Global.LONG_SIZE * 3 + SIZE];
                    backup_payload[0]=(byte)Data.Type.BACKUP.ordinal();
                    Bits.writeLong(req_id, backup_payload, 1);
                    UUID sender=(UUID)msg.src(); // must be a UUID... :-)
                    Bits.writeLong(sender.getLeastSignificantBits(), backup_payload, 9);
                    Bits.writeLong(sender.getMostSignificantBits(), backup_payload, 17);
                    send(backup, backup_payload);
                }
                break;
            case BACKUP:
                req_id=Bits.readLong(buf, 1);
                UUID final_dest=new UUID(Bits.readLong(buf, 9), Bits.readLong(buf, 17));
                ack_payload=new byte[Global.BYTE_SIZE +Global.LONG_SIZE + SIZE];
                ack_payload[0]=(byte)Data.Type.ACK.ordinal();
                Bits.writeLong(req_id, ack_payload, 1);
                send(msg.src(), ack_payload);
                break;
            case CLEAR:
                ; // no-op
                break;
        }
    }

    public void receive(MessageBatch batch) {
        for(Message msg: batch)
            receive(msg);
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
        /*for(String key: keys) {
            switch(key) {
                case "dummy":
                    m.put("dummy.req-table", req_table.toString());
                    m.put("dummy.avg_batch_size", avg_batch_size.toString());
                    m.put("tri.num_single_msgs_received", String.valueOf(num_single_msgs_received.sum()));
                    m.put("tri.num_data_batches_received", String.valueOf(num_data_batches_received.sum()));
                    m.put("tri.put-queue", put_queue.toString());
                    break;
                case "dummy.compact":
                    boolean result=req_table.compact();
                    m.put("compact", String.valueOf(result));
                    break;
                case "dummy.reset":
                    Stream.of(avg_batch_size, avg_batch_processing_time, avg_put_processing_time)
                      .forEach(AverageMinMax::clear);
                    Stream.of(num_single_msgs_received, num_data_batches_received)
                      .forEach(LongAdder::reset);
                    break;
            }
        }*/

        return m;
    }

    public String[] supportedKeys() {
        return new String[]{"dummy", "dummy.compact", "dummy.reset"};
    }

    protected void send(Address dest, byte[] payload) {
        try {
            ch.send(dest, payload);
        }
        catch(Exception e) {
            log.error("failed sending message", e);
        }
    }


    protected void handleAck(long req_id) {
        CompletableFuture<V> future=req_table.remove(req_id);
        if(future != null)
            future.complete((V)ACK_PAYLOAD);
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
    private static int estimatedSizeOf(Object obj) {
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
