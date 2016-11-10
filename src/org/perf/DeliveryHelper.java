package org.perf;

import org.jgroups.Global;
import org.jgroups.Header;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.stack.DiagnosticsHandler;
import org.jgroups.util.AverageMinMax;
import org.jgroups.util.MessageBatch;
import org.jgroups.util.Util;

import java.io.DataInput;
import java.io.DataOutput;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * @author Bela Ban
 */
public class DeliveryHelper implements DiagnosticsHandler.ProbeHandler {
    protected static final AverageMinMax avg_delivery_time=new AverageMinMax();
    protected static final AverageMinMax avg_batch_size_received=new AverageMinMax();
    protected static final AverageMinMax avg_batch_size_delivered=new AverageMinMax();
    protected static final AtomicInteger num_single_msgs_received=new AtomicInteger(0);
    protected static final AtomicInteger num_msgs_delivered=new AtomicInteger(0); // single or batch
    protected static final AtomicInteger num_batches_received=new AtomicInteger(0);
    protected static final AtomicInteger num_batches_delivered=new AtomicInteger(0);

    protected static final short PROT_ID=1025;

    static {
        ClassConfigurator.addProtocol(PROT_ID, PerfHeader.class);
    }


    public void channelCreated(JChannel ch) {
        ch.getProtocolStack().getTransport().registerProbeHandler(this);
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void messageReceived(Message msg) {
        num_single_msgs_received.incrementAndGet();
        PerfHeader hdr=new PerfHeader(Util.micros());
        msg.putHeader(PROT_ID, hdr);
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void batchesReceived(MessageBatch[] batches) {
        if(batches == null || batches.length == 0)
            return;

        for(MessageBatch batch: batches) {
            if(batch == null)
                continue;
            int size=batch.size();
            num_batches_received.incrementAndGet();
            avg_batch_size_received.add(size);
            long time=Util.micros();
            for(Message msg: batch) {
                if(msg != null)
                    msg.putHeader(PROT_ID, new PerfHeader(time));
            }
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void beforeMessageDelivery(Message msg) {
        num_msgs_delivered.incrementAndGet();
        PerfHeader hdr=msg.getHeader(PROT_ID);
        if(hdr != null) {
            long time=Util.micros() - hdr.receive_time;
            avg_delivery_time.add(time);
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void beforeBatchDelivery(MessageBatch batch) {
        num_batches_delivered.incrementAndGet();
        int size=batch.size();
        avg_batch_size_delivered.add(size);
        /*for(Message msg: batch) {
            if(msg == null)
                continue;
            PerfHeader hdr=msg.getHeader(PROT_ID);
            if(hdr != null) {
                long time=Util.micros() - hdr.receive_time;
                avg_delivery_time.add(time);
            }
        }*/
    }

    public Map<String,String> handleProbe(String... keys) {
        Map<String,String> map=new HashMap<>();
        for(String key: keys) {
            switch(key) {
                case "delivery":
                    addStats(map);
                    break;
                case "delivery-reset":
                    reset();
                    break;
            }
        }
        return map;
    }

    public String[] supportedKeys() {
        return new String[]{"delivery", "delivery-reset"};
    }


    protected static void reset() {
        avg_delivery_time.clear();
        avg_batch_size_received.clear();
        avg_batch_size_delivered.clear();
        for(AtomicInteger ai: Arrays.asList(num_single_msgs_received, num_msgs_delivered,
                                            num_batches_received, num_batches_delivered))
            ai.set(0);
    }

    protected static void addStats(Map<String,String> map) {
        map.put("avg_delivery_time",         avg_delivery_time.toString());
        map.put("avg_batch_size_received",   avg_batch_size_received.toString());
        map.put("avg_batch_size_delivered",  avg_batch_size_delivered.toString());
        map.put("num_single_msgs_received",  num_single_msgs_received.toString());
        map.put("num_msgs_delivered",        num_msgs_delivered.toString());
        map.put("num_batches_received",      num_batches_received.toString());
        map.put("num_batches_delivered",     num_batches_delivered.toString());
    }



    protected static class PerfHeader extends Header {
        protected static final short ID=1024;
        static {
            ClassConfigurator.add(ID, PerfHeader.class);
        }

        protected long receive_time; // in micros

        public PerfHeader() {
        }

        public PerfHeader(long receive_time) {
            this.receive_time=receive_time;
        }

        public long       receiveTime()       {return receive_time;}
        public PerfHeader receiveTime(long t) {this.receive_time=t; return this;}

        public short getMagicId() {
            return ID;
        }

        public Supplier<? extends Header> create() {
            return PerfHeader::new;
        }

        public int serializedSize() {
            return Global.LONG_SIZE;
        }

        public void writeTo(DataOutput out) throws Exception {
            out.writeLong(receive_time);
        }

        public void readFrom(DataInput in) throws Exception {
            receive_time=in.readLong();
        }
    }
}
