package org.cache.impl;

import org.cache.Cache;
import org.infinispan.context.Flag;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.stack.DiagnosticsHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.infinispan.context.Flag.*;

/**
 * @author Bela Ban
 * @since x.y
 */
public class InfinispanCache<K,V> implements Cache<K,V>, DiagnosticsHandler.ProbeHandler {
    protected final org.infinispan.Cache<K,V> cache;
    protected final org.infinispan.Cache<K,V> async_cache;
    protected final JGroupsTransport          transport;

    public InfinispanCache(org.infinispan.Cache<K,V> cache) {
        this.cache=cache;
        this.async_cache=cache.getAdvancedCache()
          .withFlags(IGNORE_RETURN_VALUES, SKIP_LOCKING, FORCE_ASYNCHRONOUS, SKIP_LISTENER_NOTIFICATION);
        this.transport=(JGroupsTransport)getTransport();
        transport.getChannel().stack().getTransport().registerProbeHandler(this);
    }

    public V put(K key, V value) {
        return cache.put(key, value);
    }

    @Override
    public void putAsync(K key, V value) {
        async_cache.put(key, value);
    }

    public V get(K key) {
        return cache.get(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public boolean isEmpty() {
        return cache.isEmpty();
    }

    public Set<K> keySet() {
        return cache.keySet();
    }

    public Map<K,V> getContents() {
        return new HashMap<>(cache.getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL));
    }

    public Transport getTransport() {
        RpcManager rpc_mgr=cache.getAdvancedCache().getRpcManager();
        return rpc_mgr != null? rpc_mgr.getTransport() : null;
    }

    @Override
    public Object getLocalAddress() {
        return transport.getAddress();
    }

    @Override
    public List<? extends Object> getView() {
        return transport.getMembers();
    }

    @Override
    public String[] supportedKeys() {
        return new String[]{"ispn"};
    }

    @Override
    public Map<String,String> handleProbe(String... keys) {
        for(String key: keys) {
            if("ispn".equals(key)) {
                Map<String,String> m=new HashMap<>(2);
                List<Address> mbrs=transport.getMembers();
                Address coord=transport.getCoordinator();
                m.put("addr", String.format("%s", getLocalAddress()));
                m.put("view", String.format("[%s|%d] (%d) %s", coord, transport.getViewId(), mbrs.size(), mbrs));
                m.put("ips", String.format("%s", transport.getMembersPhysicalAddresses()));
                return m;
            }
        }
        return null;
    }


}
