package org.perf;

import java.io.FileNotFoundException;
import java.util.Set;
import java.util.stream.IntStream;

import org.jgroups.util.Util;

import com.hazelcast.config.FileSystemXmlConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.PartitionContainer;
import com.hazelcast.map.impl.proxy.MapProxyImpl;
import com.hazelcast.map.impl.record.Record;
import com.hazelcast.map.impl.recordstore.RecordStore;
import com.hazelcast.partition.PartitionService;
import com.hazelcast.spi.impl.NodeEngine;

/**
 * @author Bela Ban
 * @since x.y
 */
public class HcTest2 {
    protected HazelcastInstance     hc;
    protected PartitionService ps;
    protected IMap<Integer,Integer> map;

    protected void start(String config) throws FileNotFoundException {
        com.hazelcast.config.Config conf=new FileSystemXmlConfig(config);
        hc=Hazelcast.newHazelcastInstance(conf);
        ps=hc.getPartitionService();
        map=hc.getMap("perf");

        eventLoop();


        hc.shutdown();
    }

    protected void eventLoop() {
        boolean looping=true;
        while(looping) {
            int c=Util.keyPress("[1] put [2] local [x] exit");
            switch(c) {
                case '1':
                    IntStream.rangeClosed(1, 5).forEach(n -> map.put(n, n));
                    break;
                case '2':
                    Set<Integer> key_set=map.keySet();
                    System.out.printf("**** global key set: %s\n", key_set);
                    NodeEngine engine1=((MapProxyImpl)map).getNodeEngine();
                    MapService map_service1=engine1.getService("hz:impl:mapService");
                    MapServiceContext ctx1=map_service1.getMapServiceContext();
                    print(key_set, map, "m1", ps, ctx1);
                    break;
                case 'x':
                    looping=false;
                    break;
            }
        }
    }

    protected static Object toObj(Object data, MapServiceContext ctx) {
        return ctx.toObject(data);
    }

    protected static void print(Set<Integer> key_set, IMap<Integer,Integer> map, String name, PartitionService ps, MapServiceContext ctx) {
        System.out.printf("\n*** local keys %s: %s\n", name, map.localKeySet());
        System.out.printf("**** keys in %s:\n", name);
        for(int i: key_set) {
            int id=ps.getPartition(i).getPartitionId();
            PartitionContainer partitionContainer = ctx.getPartitionContainer(id);
            RecordStore<Record<byte[]>> store=partitionContainer.getRecordStore("perf");

            store.forEach((data, record) -> System.out.printf("key: %s, value: %s\n", toObj(data, ctx), toObj(record.getValue(), ctx)), false);
        }
    }

    public static void main(String[] args) throws Exception {
        new HcTest2().start(args[0]);
    }

}
