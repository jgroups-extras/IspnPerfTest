package org.cache.impl;

import org.cache.Cache;
import org.jgroups.*;
import org.jgroups.blocks.MethodCall;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.util.Util;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cache which simulates the way Infinispan works, but doesn't support rehashing. Fixed replication count of 2.
 * A PUT is sent via a blocking unicast RPC to the primary, which locks the cache, sends a BACKUP to the backup node
 * <em>asynchronously</em>, then returns from PUT. The cost of a PUT is more or less 2x latency (1 round-trip).
 * <p>
 * The stream of changes to a primary is serialized by the ReentrantLock which orders lock acquisitions fairly (like
 * a queue, in order of arrival). Because JGroups orders BACKUP messages (in send order), all BACKUP commands are
 * applied by backup nodes in the same order as on the primary node, so data at the primary and backup is always consistent.
 * <p>
 * If consistent_gets is true, then the lock is also acquired on a GET, which causes the GET to be inserted into the
 * queue of the ReentrantLock in the right order with respect to PUTs. E.g. if a caller invokes PUT(x=2) and then a
 * GET(x), the result will be x=2, or a later value (read-your-writes).
 *
 * @author Bela Ban
 * @since  1.0
 */
public class DistCache<K,V> implements Cache<K,V>, Closeable {
    protected final Map<K,V>     map=new ConcurrentHashMap<>();
    protected JChannel           ch;
    protected RpcDispatcher      disp;
    protected Address            local_addr;
    protected volatile Address[] members;
    protected final Lock         lock=new ReentrantLock(true); // serializes access to the cache for puts (fair = fifo order of reqs)
    protected boolean            consistent_gets=true; // if true, GETs are ordered correctly wrt PUTs
    protected boolean            sync_backups=true; // whether a BACKUP call is sync or async

    protected static final Map<Short,Method> methods=Util.createConcurrentMap(8);
    protected static final short PUT    = 1;
    protected static final short GET    = 2;
    protected static final short CLEAR  = 3;
    protected static final short BACKUP = 4;
    protected static final MethodCall     CLEAR_CALL;
    protected static final RequestOptions SYNC_OPTS=RequestOptions.SYNC();
    protected static final RequestOptions ASYNC_BACKUP=RequestOptions.ASYNC();
    protected static final RequestOptions SYNC_BACKUP=RequestOptions.SYNC().setFlags(Message.Flag.OOB);

    static {
        try {
            methods.put(PUT, DistCache.class.getMethod("_put", Object.class, Object.class));
            methods.put(GET, DistCache.class.getMethod("_get", Object.class));
            methods.put(CLEAR, DistCache.class.getMethod("_clear"));
            methods.put(BACKUP, DistCache.class.getMethod("_backup", Object.class, Object.class));
            CLEAR_CALL=new MethodCall(CLEAR);
        }
        catch(Throwable t) {
            throw new RuntimeException("failed finding methods", t);
        }
    }


    public DistCache(String config) throws Exception {
        ch=new JChannel(config);
        disp=new RpcDispatcher(ch, this);
        disp.setMethodLookup(methods::get);
        disp.setMembershipListener(new MembershipListener() {
            public void viewAccepted(View new_view) {
                System.out.printf("view: %s\n", new_view);
                members=new_view.getMembersRaw();
            }
            public void suspect(Address suspected_mbr) {}
            public void block() {}
            public void unblock() {}
        });
        ch.connect("dist");
        this.local_addr=ch.getAddress();
    }

    public boolean   getConsistentGets()             {return consistent_gets;}
    public DistCache setConsistentGets(boolean flag) {consistent_gets=flag; return this;}
    public boolean   getSyncBackups()                {return sync_backups;}
    public DistCache setSyncBackups(boolean flag )   {this.sync_backups=flag; return this;}

    public void close() throws IOException {
        Util.close(disp, ch);
    }

    /**
     * Invokes a blocking _put(key,value) on the primary, which locks the local cache, makes the change,
     * invokes a non-blocking _backup(key,value) on the backup and returns. This ensures that the backups apply the
     * changes in the same way as the primary nodes.
     * @param key the new key
     * @param value the new value
     */
    public V put(K key, V value) {
        int hash=hash(key);
        Address primary=pickMember(hash, 0);
        MethodCall put_call=new MethodCall(PUT, key, value);
        try {
            return disp.callRemoteMethod(primary, put_call, SYNC_OPTS);
        }
        catch(Throwable t) {
            throw new RuntimeException(t);
        }
    }


    /**
     * Pick the member to which key hashes and invoke a blocking _get(key) RPC
     * @param key the key
     * @return the value associated with the key, or null if key has not been set
     */
    public V get(K key) {
        Address target=pickMember(hash(key), 0);
        if(target == null)
            throw new IllegalArgumentException("target must not be null");
        if(Objects.equals(target, local_addr))
            return map.get(key);
        try {
            return disp.callRemoteMethod(target, new MethodCall(GET, key), SYNC_OPTS);
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void clear() {
        try {
            disp.callRemoteMethods(null, CLEAR_CALL, SYNC_OPTS);
        }
        catch(Throwable t) {
            throw new RuntimeException(t);
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

    /**
     * Locks the cache, applies the change, sends a BACKUP message to the backup node asynchronously, unlocks the cache
     * and returns
     */
    public V _put(K key, V value) {
        V retval;
        MethodCall backup_call=new MethodCall(BACKUP, key, value);

        lock.lock();
        try {
            retval=map.put(key,value);
            int hash=hash(key);
            Address backup=pickMember(hash, 1);
            RequestOptions opts=sync_backups? SYNC_BACKUP : ASYNC_BACKUP;
            disp.callRemoteMethod(backup, backup_call, opts);
            return retval;
        }
        catch(Throwable t) {
            throw new RuntimeException(t);
        }
        finally {
            lock.unlock();
        }
    }

    public void _backup(K key, V value) {
            map.put(key, value);
        }

    public V _get(K key) {
        if(!consistent_gets)
            return map.get(key);
        lock.lock();
        try {
            return map.get(key);
        }
        finally {
            lock.unlock();
        }
    }

    public void _clear() {
        map.clear();
    }

    protected int hash(K key) {
        return key.hashCode();
    }

    protected Address pickMember(int hash, int offset) {
        Address[] mbrs=this.members;
        int index=hash % mbrs.length;
        if(offset > 0)
            index=(index+offset) % mbrs.length;
        return mbrs[index];
    }
}
