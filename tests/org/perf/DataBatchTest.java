package org.perf;

import org.cache.impl.tri.Data;
import org.cache.impl.tri.DataBatch;
import org.jgroups.Address;
import org.jgroups.util.Util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * @author Bela Ban
 * @since  1.0
 */
@org.testng.annotations.Test
public class DataBatchTest {
    protected static final Address addr=Util.createRandomAddress("A");

    public void testIterator() {
        DataBatch batch=new DataBatch(addr, 10);
        Stream.of(TriCacheTest.create()).forEach(batch::add);
        System.out.println("batch = " + batch);

        AtomicInteger num=new AtomicInteger(0);

        batch.stream().forEach(data -> {
            System.out.printf("data: %s\n", data);
            num.incrementAndGet();
        });
        assert num.get() == 10;

        num.set(0);
        batch.streamOf(Data.Type.ACK).forEach(data -> {
            System.out.printf("data: %s\n", data);
            num.incrementAndGet();
        });

        assert num.get() == 2;
    }

    public void testIterator2() {
        DataBatch batch=new DataBatch(addr, 10);
        Stream.of(TriCacheTest.create()).forEach(batch::add);
        System.out.println("batch = " + batch);

        AtomicInteger num=new AtomicInteger(0);

        batch.streamOf(Data.Type.ACK).forEach(data -> {
            System.out.printf("data: %s\n", data);
            num.incrementAndGet();
        });

        assert num.get() == 2;
        num.set(0);

        batch.streamOf(Data.Type.PUT).forEach(data -> {
            System.out.printf("data: %s\n", data);
            num.incrementAndGet();
        });

        assert num.get() == 3;
    }
}
