package main.test.org.perf;

import main.java.org.cache.impl.tri.Data;
import main.java.org.cache.impl.tri.DataBatch;

import java.util.stream.Stream;

import static main.java.org.cache.impl.tri.Data.Type.*;

/**
 * @author Bela Ban
 * @since  1.0
 */
@org.testng.annotations.Test
public class TriCacheTest {

    public void testDataBatchAdd() {
        DataBatch batch=new DataBatch(null, 5);
        for(int i=0; i < 5; i++) {
            Data d=new Data(PUT, 322649+i, i, new byte[1024], null);
            batch.add(d);
        }
        System.out.println("batch = " + batch);
        assert batch.capacity() == 5;
        assert batch.size() == 5;

        batch.add(new Data(GET, 300, 4, null, null));
        System.out.println("batch = " + batch);
        assert batch.capacity() == 6;
        assert batch.size() == 6;
    }

    public void testDataBatchCount() {
        DataBatch batch=new DataBatch(null, 5);
        Stream.of(create()).forEach(batch::add);
        System.out.println("batch = " + batch);
        int[] count=new int[5];
        batch.count(count);
        assert count[0] == 3;
        assert count[1] == 3;
        assert count[2] == 2;
        assert count[3] == 1;
        assert count[4] == 1;
    }


    public static Data[] create() {
        Data[] data=new Data[10];
        data[0]=new Data(PUT, 1, 1, new byte[1024], null);
        data[1]=new Data(BACKUP, 3, 3, new byte[1024], null);
        data[2]=new Data(GET, 5, 5, null, null);
        data[3]=new Data(GET, 6, 6, null, null);
        data[4]=new Data(ACK, 5, 5, new byte[1024], null);
        data[5]=new Data(ACK, 5, 5, new byte[1024], null);
        data[6]=new Data(CLEAR, 0, 0, null, null);
        data[7]=new Data(GET, 6, 6, null, null);
        data[8]=new Data(PUT, 4, 4, new byte[1024], null);
        data[9]=new Data(PUT, 2, 2, new byte[1024], null);
        return data;
    }
}
