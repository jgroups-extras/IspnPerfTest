package org.cache.impl;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.PartitionContainer;
import com.hazelcast.map.impl.proxy.MapProxyImpl;
import com.hazelcast.map.impl.recordstore.RecordStore;
import com.hazelcast.partition.PartitionService;
import com.hazelcast.spi.impl.NodeEngine;

import org.cache.Cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author Bela Ban
 * @since x.y
 */
public class HazelcastCache<K,V> implements Cache<K,V> {
    protected final HazelcastInstance hc;
    protected final IMap<K,V>         cache;
    protected final String            cache_name;


    public HazelcastCache(HazelcastInstance hc, String cache_name) {
        this.hc=hc;
        this.cache_name=cache_name;
        this.cache=hc.getMap(this.cache_name);
    }

    public V put(K key, V value) {
        cache.set(key, value);
        return null;
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

        // Hacky code ahead! Hazelcast doesn't seem to have a simple way to retrieve all local data; instead
        // localKeySet() only returns keys _owned_ by this member

        PartitionService ps=hc.getPartitionService();
        NodeEngine engine=((MapProxyImpl)cache).getNodeEngine();
        MapService map_service=engine.getService("hz:impl:mapService");
        MapServiceContext ctx=map_service.getMapServiceContext();

        // Keys present on all nodes (this is a distributed task)
        Set<K> global_keys=keySet();
        Map<K,V> map=new HashMap<>(global_keys.size()); // max, usually less
        for(K k: global_keys) {
            int id=ps.getPartition(k).getPartitionId();
            PartitionContainer partitionContainer = ctx.getPartitionContainer(id);
            RecordStore store=partitionContainer.getRecordStore(this.cache_name);
            Object val=store.get(ctx.toData(k), false, hc.getCluster().getLocalMember().getAddress());
            if(val != null)
                map.put(k, (V)toObj(val, ctx));
        }
        return map;
    }

    protected static Object toObj(Object data, MapServiceContext ctx) {
        return ctx.toObject(data);
    }
}
