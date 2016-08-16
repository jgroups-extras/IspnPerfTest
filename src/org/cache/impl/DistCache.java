package org.cache.impl;

import org.cache.Cache;
import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.MembershipListener;
import org.jgroups.View;
import org.jgroups.blocks.MethodCall;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.ResponseMode;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.util.FutureListener;
import org.jgroups.util.Rsp;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Cache which simulates the way Infinispan works, but doesn't support rehashing. Fixed replication count of 2
 * @author Bela Ban
 * @since x.y
 */
public class DistCache<K,V> implements Cache<K,V>, Closeable {
    protected final Map<K,V>     map=new ConcurrentHashMap<>();
    protected JChannel           ch;
    protected RpcDispatcher      disp;
    protected Address            local_addr;
    protected volatile Address[] members;
    protected boolean            mcast_puts;

    protected static final Map<Short,Method> methods=Util.createConcurrentMap(8);
    protected static final short PUT   = 1;
    protected static final short GET   = 2;
    protected static final short CLEAR = 3;
    protected static final MethodCall     CLEAR_CALL;
    protected static final RequestOptions SYNC_OPTS=RequestOptions.SYNC();
    protected static final RequestOptions GET_FIRST_OPTS=RequestOptions.SYNC().setMode(ResponseMode.GET_FIRST);

    static {
        try {
            methods.put(PUT, DistCache.class.getMethod("_put", Object.class, Object.class));
            methods.put(GET, DistCache.class.getMethod("_get", Object.class));
            methods.put(CLEAR, DistCache.class.getMethod("_clear"));
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

    public DistCache setMcastPuts(boolean flag) {this.mcast_puts=flag; return this;}
    public boolean   getMcastPuts()             {return mcast_puts;}

    public void close() throws IOException {
        Util.close(disp, ch);
    }

    public V put(K key, V value) {
        return mcast_puts? putWithMcast(key, value) : putWithUnicasts(key, value);
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
        return map.put(key, value);
    }

    public V _get(K key) {
        return map.get(key);
    }

    public void _clear() {
        map.clear();
    }

    /**
     * Picks the primary and secondary owner and sends a multicast Returns the first result.
     * @param key the new key
     * @param value the new value
     * @return the previous value (or null) associated with key
     */
    protected V putWithMcast(K key, V value) {
        int hash=hash(key);
        Address primary=pickMember(hash, 0), secondary=pickMember(hash, 1);
        MethodCall put_call=new MethodCall(PUT, key, value);

        try {
            RspList<V> rsps=disp.callRemoteMethods(Arrays.asList(primary, secondary), put_call, GET_FIRST_OPTS);
            for(Rsp<V> rsp: rsps) {
                if(rsp.wasReceived())
                    return rsp.getValue();
            }
            throw new RuntimeException("no valid response received");
        }
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Picks the primary and secondary owner and sends 2 blocking unicasts. Waits for the first of the 2 futures that
     * return.
     * @param key the new key
     * @param value the new value
     */
    protected V putWithUnicasts(K key, V value) {
        int hash=hash(key);
        Address primary=pickMember(hash, 0), secondary=pickMember(hash, 1);
        MethodCall put_call=new MethodCall(PUT, key, value);

        final CompletableFuture<V> f1=new CompletableFuture<>(), f2=new CompletableFuture<>();

        FutureListener<V> l1=createListener(f1), l2=createListener(f2);

        try {
            disp.callRemoteMethodWithFuture(primary, put_call, GET_FIRST_OPTS, l1)
              .setListener(l1);
        }
        catch(Exception e) {
            e.printStackTrace();
        }

        try {
            disp.callRemoteMethodWithFuture(secondary, put_call, GET_FIRST_OPTS, l2)
              .setListener(l2);
        }
        catch(Exception e) {
            e.printStackTrace();
        }

        CompletableFuture<Object> any=CompletableFuture.anyOf(f1, f2);
        try {
            return (V)any.get(10000, TimeUnit.MILLISECONDS);
        }
        catch(Throwable t) {
            throw new RuntimeException(t);
        }
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
