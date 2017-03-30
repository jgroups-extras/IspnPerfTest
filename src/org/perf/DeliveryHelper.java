package org.perf;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static org.perf.Test.PERCENTILES;

/**
 * @author Bela Ban
 */
public class DeliveryHelper implements DiagnosticsHandler.ProbeHandler {
    // The average time (in micros) from reception of a message until just before delivery (delivery time is excluded)
    protected static final Recorder avg_receive_time=new Recorder(1, 80_000_000, 3);

    protected static final Recorder avg_delivery_time=new Recorder(1, 80_000_000, 3);

    // The average time (in micros) from JChannel.down(Message) until _after_ the message has been put on the network
    protected static final Recorder avg_send_time=new Recorder(1, 80_000_000, 3);

    protected static final AverageMinMax avg_batch_size_received=new AverageMinMax();


    // sets and gets microseconds recorded by threads
    protected static final ConcurrentMap<Thread,Long> receive_timings=new ConcurrentHashMap<>();

    protected static final ConcurrentMap<Thread,Long> delivery_timings=new ConcurrentHashMap<>();


    protected static final short PROT_ID=1025;

    static {
        ClassConfigurator.addProtocol(PROT_ID, PerfHeader.class);
    }


    @SuppressWarnings("MethodMayBeStatic")
    public void recordReceiveTime() {
        receive_timings.put(Thread.currentThread(), Util.micros());
    }

    @SuppressWarnings("MethodMayBeStatic")
    public long getReceiveTime() {
        return receive_timings.get(Thread.currentThread());
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void recordDeliveryTime() {
        delivery_timings.put(Thread.currentThread(), Util.micros());
    }

    @SuppressWarnings("MethodMayBeStatic")
    public long getDeliveryTime() {
           return delivery_timings.get(Thread.currentThread());
       }


    public void channelCreated(JChannel ch) {
        ch.getProtocolStack().getTransport().registerProbeHandler(this);
    }

    public void addCurrentReceiveTimeTo(Message msg) {
        addReceiveTimeTo(msg, Util.micros());
    }

    public void addCurrentSendTimeTo(Message msg) {
        addSendTimeTo(msg, Util.micros());
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void addReceiveTimeTo(Message msg, long time) {
        PerfHeader hdr=new PerfHeader(time, 0);
        msg.putHeader(PROT_ID, hdr);
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void addSendTimeTo(Message msg, long time) {
        PerfHeader hdr=new PerfHeader(0, time);
        msg.putHeader(PROT_ID, hdr);
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void messageDeserialized(Message msg) {
        long previously_recorded_time=getReceiveTime();
        if(previously_recorded_time > 0)
            addReceiveTimeTo(msg, previously_recorded_time);
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void batchesReceived(MessageBatch[] batches) {
        if(batches == null || batches.length == 0)
            return;

        long time=getReceiveTime(); // previously recorded in TP.receive()
        if(time == 0)
            return;

        PerfHeader perf_hdr=new PerfHeader(time, 0);
        for(MessageBatch batch: batches) {
            if(batch == null)
                continue;
            int size=batch.size();
            avg_batch_size_received.add(size);
            for(Message msg: batch) {
                if(msg != null)
                    msg.putHeader(PROT_ID, perf_hdr);
            }
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void beforeMessageDelivery(Message msg) {
        PerfHeader hdr=msg.getHeader(PROT_ID);
        if(hdr != null && hdr.receive_time > 0) {
            long time=Util.micros() - hdr.receive_time;
            avg_receive_time.recordValue(time);
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void afterMessageSendByTransport(Message msg) {
        PerfHeader hdr=msg.getHeader(PROT_ID);
        if(hdr != null && hdr.send_time > 0) {
            long time=Util.micros() - hdr.send_time;
            avg_send_time.recordValue(time);
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void afterMessageBatchSendByTransport(List<Message> list) {
        if(list != null) {
            long current_time=Util.micros();
            for(Message msg: list) {
                PerfHeader hdr=msg.getHeader(PROT_ID);
                if(hdr != null && hdr.send_time > 0) {
                    long time=current_time - hdr.send_time;
                    avg_send_time.recordValue(time);
                }
            }
        }
    }


    public void afterChannelUp() {
        long previously_recorded_time=getDeliveryTime();
        if(previously_recorded_time > 0) {
            long time=Util.micros() - previously_recorded_time;
            avg_delivery_time.recordValue(time);
        }
    }

    public void afterChannelUpBatch(int batch_size) {
         long previously_recorded_time=getDeliveryTime();
         if(previously_recorded_time > 0) {
             long time=Util.micros() - previously_recorded_time;
             if(batch_size > 1)
                 time/=batch_size;
             avg_delivery_time.recordValue(time);
         }
     }

    @SuppressWarnings("MethodMayBeStatic")
    public void beforeBatchDelivery(MessageBatch batch) {
        int size=batch.size();
        if(size > 0) {
            Message first=batch.first();
            PerfHeader hdr=first.getHeader(PROT_ID);
            if(hdr != null && hdr.receive_time > 0) {
                long time=Util.micros() - hdr.receive_time;
                if(size > 1)
                    time=time/size;
                avg_receive_time.recordValue(time);
            }
        }
    }


    public Map<String,String> handleProbe(String... keys) {
        Map<String,String> map=new LinkedHashMap<>();
        for(String key: keys) {
            switch(key) {
                case "timings":
                    addStats(map, false);
                    break;
                case "timings-percentiles":
                    addStats(map, true);
                    break;
                case "timings-reset":
                    reset();
                    break;
            }
        }
        return map;
    }

    public String[] supportedKeys() {
        return new String[]{"timings", "timings-percentiles", "timings-reset"};
    }


    protected static void reset() {
        avg_receive_time.reset();
        avg_delivery_time.reset();
        avg_send_time.reset();
        avg_batch_size_received.clear();
    }

    protected static void addStats(Map<String,String> map, boolean print_details) {
        map.put("avg_receive_time",        print(avg_receive_time.getIntervalHistogram(), print_details));
        map.put("avg_delivery_time",       print(avg_delivery_time.getIntervalHistogram(), print_details));
        map.put("avg_send_time",           print(avg_send_time.getIntervalHistogram(), print_details));
        map.put("avg_batch_size_received", avg_batch_size_received.toString());
    }

    protected static String print(Histogram avg, boolean details) {
        if(avg == null || avg.getTotalCount() == 0)
            return "n/a";
        return details?
          String.format("min/avg/max = %d/%,.2f/%,.2f us (%s)",
                        avg.getMinValue(), avg.getMean(), avg.getMaxValueAsDouble(), percentiles(avg)) :
          String.format("min/avg/max = %d/%,.2f/%,.2f us",
                        avg.getMinValue(), avg.getMean(), avg.getMaxValueAsDouble());
    }

    protected static String percentiles(Histogram h) {
        StringBuilder sb=new StringBuilder();
        for(double percentile: PERCENTILES) {
            long val=h.getValueAtPercentile(percentile);
            sb.append(String.format("%,.1f=%,d ", percentile, val));
        }
        sb.append(String.format("[percentile at mean: %,.2f]", h.getPercentileAtOrBelowValue((long)h.getMean())));
        return sb.toString();
    }



    protected static class PerfHeader extends Header {
        protected static final short ID=1024;
        static {
            ClassConfigurator.add(ID, PerfHeader.class);
        }

        protected long receive_time, send_time; // in micros

        public PerfHeader() {
        }

        public PerfHeader(long receive_time, long send_time) {
            this.receive_time=receive_time;
            this.send_time=send_time;
        }


        public short getMagicId() {
            return ID;
        }

        public Supplier<? extends Header> create() {
            return PerfHeader::new;
        }

        public int serializedSize() {
            return Global.LONG_SIZE*2;
        }

        public void writeTo(DataOutput out) throws Exception {
            out.writeLong(receive_time);
            out.writeLong(send_time);
        }

        public void readFrom(DataInput in) throws Exception {
            receive_time=in.readLong();
            send_time=in.readLong();
        }
    }
}
