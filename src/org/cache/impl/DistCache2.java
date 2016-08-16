package org.cache.impl;

import org.cache.Cache;
import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.MembershipListener;
import org.jgroups.View;
import org.jgroups.blocks.MethodCall;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.util.FutureListener;
import org.jgroups.util.Util;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cache which simulates the way Infinispan works, but doesn't support rehashing. Fixed replication count of 2.
 * A PUT is sent via a blocking unicast RPC to the primary, which locks the cache, sends a BACKUP to the backup node
 * *asynchronously*, then returns from PUT. The cost of a PUT is more or less 2x latency.
 * @author Bela Ban
 * @since x.y
 */
public class DistCache2<K,V> implements Cache<K,V>, Closeable {
    protected final Map<K,V>     map=new ConcurrentHashMap<>();
    protected JChannel           ch;
    protected RpcDispatcher      disp;
    protected Address            local_addr;
    protected volatile Address[] members;
    protected final Lock         lock=new ReentrantLock(true); // serializes access to the cache for puts (fair = fifo order of reqs)

    protected static final Map<Short,Method> methods=Util.createConcurrentMap(8);
    protected static final short PUT    = 1;
    protected static final short GET    = 2;
    protected static final short CLEAR  = 3;
    protected static final short BACKUP = 4;
    protected static final MethodCall     CLEAR_CALL;
    protected static final RequestOptions SYNC_OPTS=RequestOptions.SYNC();
    protected static final RequestOptions ASYNC_OPTS=RequestOptions.ASYNC();

    static {
        try {
            methods.put(PUT, DistCache2.class.getMethod("_put", Object.class, Object.class));
            methods.put(GET, DistCache2.class.getMethod("_get", Object.class));
            methods.put(CLEAR, DistCache2.class.getMethod("_clear"));
            methods.put(BACKUP, DistCache2.class.getMethod("_backup", Object.class, Object.class));
            CLEAR_CALL=new MethodCall(CLEAR);
        }
        catch(Throwable t) {
            throw new RuntimeException("failed finding methods", t);
        }
    }


    public DistCache2(String config) throws Exception {
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

    public V _put(K key, V value) {
        V retval;
        MethodCall backup_call=new MethodCall(BACKUP, key, value);

        lock.lock();
        try {
            retval=map.put(key,value);
            int hash=hash(key);
            Address backup=pickMember(hash, 1);
            disp.callRemoteMethod(backup, backup_call, ASYNC_OPTS);
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
        return map.get(key);
    }

    public void _clear() {
        map.clear();
    }



    protected FutureListener<V> createListener(CompletableFuture<V> f) {
        return future -> {
            try {
                f.complete(future.get());
            }
            catch(Throwable t) {
                f.completeExceptionally(t);
            }
        };
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
