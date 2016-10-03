package org.cache.impl;

import org.cache.Cache;
import org.jgroups.*;
import org.jgroups.util.Bits;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.Streamable;
import org.jgroups.util.Util;

import java.io.Closeable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cache which simulates the way Infinispan "triangle" works, but doesn't support rehashing. Fixed replication count of 2.
 * A PUT is sent to the primary which applies the change and forwards it to the backup node. The backup node also applies
 * the change and sends an ACK back to the caller. The caller blocks until it gets the ACK with the value, or a timeout
 * kicks in.
 * <p>
 * GETs always go to the primary node.
 * @author Bela Ban
 * @since  1.0
 */
public class TriCache<K,V> extends ReceiverAdapter implements Cache<K,V>, Closeable {
    protected final Map<K,V>                       map=new ConcurrentHashMap<>();
    protected JChannel                             ch;
    protected Address                              local_addr;
    protected volatile Address[]                   members;

    // Serializes access to the cache for puts (fair = fifo order of reqs)
    protected final Lock                           lock=new ReentrantLock(true);

    // Maps req-ids to futures on which callers block (e.g. put() or get()) until an ACK has been received
    protected final Map<Long,CompletableFuture<V>> req_table=new ConcurrentHashMap<>();
    protected static final AtomicLong              REQ_IDs=new AtomicLong(1);

    protected static final short PUT    = 1; // async to backup node
    protected static final short GET    = 2; // sync to primary node, response: ACK
    protected static final short CLEAR  = 3; // async to all
    protected static final short BACKUP = 4; // async to backup node, response: ACK to original sender
    protected static final short ACK    = 5; // async from backup to to original sender



    public TriCache(String config) throws Exception {
        ch=new JChannel(config);
        ch.setReceiver(this);
        ch.connect("tri");
        this.local_addr=ch.getAddress();
    }


    public void close() throws IOException {
        Util.close(ch);
    }

    /**
     * Sends a PUT message to the primary and blocks until an ACK has been received (from the backup node)
     * @param key the new key
     * @param value the new value
     */
    public V put(K key, V value) {
        int hash=hash(key);
        Address primary=pickMember(hash, 0);
        CompletableFuture<V> future=new CompletableFuture<>(); // used to block for response (or timeout)
        long req_id=REQ_IDs.getAndIncrement();
        req_table.put(req_id, future);

        try {
            Data data=new Data(PUT, req_id, key, value, null);
            if(Objects.equals(primary, local_addr))
                _put(data, local_addr);
            else
                send(primary, data, false);
            return future.get(10000, TimeUnit.MILLISECONDS);
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            req_table.remove(req_id);
        }
    }


    /**
     * Pick the member to which key hashes and invoke a blocking _get(key) RPC
     * @param key the key
     * @return the value associated with the key, or null if key has not been set
     */
    public V get(K key) {
        Address dest=pickMember(hash(key), 0);
        if(dest == null)
            throw new IllegalArgumentException("dest must not be null");
        if(Objects.equals(dest, local_addr))
            return map.get(key);

        CompletableFuture<V> future=new CompletableFuture<>(); // used to block for response (or timeout)
        long req_id=REQ_IDs.getAndIncrement();
        req_table.put(req_id, future);

        try {
            Data data=new Data(GET, req_id, key, null, null);
            send(dest, data, false);
            return future.get(10000, TimeUnit.MILLISECONDS);
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            req_table.remove(req_id);
        }
    }

    public void clear() {
        Data data=new Data(CLEAR, 0, null, null, null);
        try {
            send(null, data, false);
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

    public void receive(Message msg) {
        try {
            Data data=Util.streamableFromByteBuffer(Data.class, msg.getRawBuffer(), msg.getOffset(), msg.getLength());
            switch(data.type) {
                case PUT:
                    _put(data, msg.src());
                    break;
                case GET:
                    _get(data, msg.src());
                    break;
                case CLEAR:
                    _clear();
                    break;
                case BACKUP:
                    _backup(data);
                    break;
                case ACK:
                    _ack(data);
                    break;
                default:
                    throw new IllegalArgumentException(String.format("type %d not known", data.type));
            }
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void viewAccepted(View view) {
        System.out.printf("-- view: %s\n", view);
        members=view.getMembersRaw();
    }

    /**
     * Locks the cache, applies the change, sends a BACKUP message to the backup node asynchronously, unlocks the cache
     * and returns
     */
    public void _put(Data data, Address sender) throws Exception {
        // reuse data instead of creating a new instance
        data.type=BACKUP;
        data.original_sender=sender;
        int hash=hash((K)data.key);
        Address backup=pickMember(hash, 1);
        boolean primary_is_backup=Objects.equals(local_addr, backup);
        Message backup_msg=primary_is_backup? null : createMessage(backup, data, false);

        lock.lock();
        try {
            map.put((K)data.key, (V)data.value);
            if(primary_is_backup) { // primary == backup (e.g. when cluster size is 1: ack directly
                data.type=ACK;
                _ack(data);
            }
            else
                ch.send(backup_msg); // this will call _backup in the backup node
        }
        finally {
            lock.unlock();
        }
    }

    public void _backup(Data data) throws Exception {
        map.put((K)data.key, (V)data.value);

        // reuse data again
        data.type=ACK;
        data.key=null;
        //As we're comparing against Infinispan's Cache.withFlags(Flag.IGNORE_RETURN_VALUES); and Hazelcast's cache.set:
        data.value=null;
        Address dest=data.original_sender;
        data.original_sender=null;
        boolean local=Objects.equals(local_addr, dest);
        if(local)
            _ack(data);
        else {
            Message ack_msg=createMessage(dest, data, true);
            ch.send(ack_msg);
        }
    }

    public void _get(Data data, Address sender) throws Exception {
        // reuse data
        data.type=ACK;
        K key=(K)data.key;
        data.value=map.get(key);
        Message get_rsp=createMessage(sender, data, true);
        ch.send(get_rsp);
    }

    public void _ack(Data data) {
        CompletableFuture<V> future=req_table.get(data.req_id);
        if(future != null) {
            future.complete((V)data.value);
            req_table.remove(data.req_id); // probably not needed as the caller already does this, even on call timeout
        }
    }

    public void _clear() {
        lock.lock();
        try {
            map.clear();
        }
        finally {
            lock.unlock();
        }
    }

    protected int hash(K key) {
        return key.hashCode();
    }

    protected static Message createMessage(Address dest, Data data, boolean oob) throws Exception {
        ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(1200);
        data.writeTo(out);
        Message msg=new Message(dest, out.buffer(), 0, out.position());
        if(oob)
            msg.setFlag(Message.Flag.OOB);
        return msg;
    }

    protected void send(Address dest, Data data, boolean oob) throws Exception {
        ch.send(createMessage(dest, data, oob));
    }

    protected Address pickMember(int hash, int offset) {
        Address[] mbrs=this.members;
        int index=hash % mbrs.length;
        if(offset > 0)
            index=(index+offset) % mbrs.length;
        return mbrs[index];
    }

    protected static String typeToString(short type) {
        switch(type) {
            case PUT:    return "PUT";
            case GET:    return "GET";
            case CLEAR:  return "CLEAR";
            case BACKUP: return "BACKUP";
            case ACK:    return "ACK";
            default:     return "n/a";
        }
    }

    public static class Data implements Streamable {
        protected short   type;
        protected long    req_id; // the ID of the request: unique per node
        protected Object  key;
        protected Object  value;
        protected Address original_sender; // if type == BACKUP: the backup node needs to send an ACK to the original sender

        public Data() {
        }

        public Data(short type, long req_id, Object key, Object value, Address original_sender) {
            this.type=type;
            this.req_id=req_id;
            this.key=key;
            this.value=value;
            this.original_sender=original_sender;
        }

        public void writeTo(DataOutput out) throws Exception {
            out.writeShort(type);
            switch(type) {
                case PUT:    // req_id | key | value
                case BACKUP: // + original_sender
                    Bits.writeLong(req_id, out);
                    Util.objectToStream(key, out);
                    Util.objectToStream(value, out);
                    if(type == BACKUP)
                        Util.writeAddress(original_sender, out);
                    break;
                case GET: // req_id | key
                    Bits.writeLong(req_id, out);
                    Util.objectToStream(key, out);
                    break;
                case CLEAR:
                    break;
                case ACK:    // req_id | value
                    Bits.writeLong(req_id, out);
                    Util.objectToStream(value, out);
                    break;
                default:
                    throw new IllegalStateException(String.format("type %d not known", type));
            }
        }

        public void readFrom(DataInput in) throws Exception {
            type=in.readShort();
            switch(type) {
                case PUT:    // req_id | key | value
                case BACKUP: // + original_sender
                    req_id=Bits.readLong(in);
                    key=Util.objectFromStream(in);
                    value=Util.objectFromStream(in);
                    if(type == BACKUP)
                        original_sender=Util.readAddress(in);
                    break;
                case GET: // req_id | key
                    req_id=Bits.readLong(in);
                    key=Util.objectFromStream(in);
                    break;
                case CLEAR:
                    break;
                case ACK:    // req_id | [value] (if used as GET response)
                    req_id=Bits.readLong(in);
                    value=Util.objectFromStream(in);
                    break;
                default:
                    throw new IllegalStateException(String.format("type %d not known", type));
            }
        }

        public String toString() {
            switch(type) {
                case PUT:
                    return String.format("%s req-id=%d", typeToString(type), req_id);
                case GET:
                    return String.format("%s key=%s req-id=%d", typeToString(type), key, req_id);
                case CLEAR:
                    return typeToString(type);
                case BACKUP:
                    return String.format("%s req-id=%d caller=%s", typeToString(type), req_id, original_sender);
                case ACK:
                    return String.format("%s req-id=%d", typeToString(type), req_id);
                default:
                    return "n/a";
            }
        }
    }
}
