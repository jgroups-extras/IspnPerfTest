package org.perf;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.SynchronizedHistogram;
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
    protected static final Histogram avg_receive_time=createHistogram();

    // The average time (in micros) for the invocation of JChannel.up(Message) or JChannel.up(MessageBatch)
    protected static final Histogram avg_delivery_time=createHistogram();

    // The average time (in micros) for sending a message down to the transport (excluding the time spent in the transport)
    protected static final Histogram avg_down_time=createHistogram();

    // The average time (in micros) from JChannel.down(Message) until _after_ the message has been put on the network
    protected static final Histogram avg_send_time=createHistogram();

    // The average time (in micros) for sending a message (excluding the time spent in the bundler)
    protected static final Histogram avg_transport_send_time=createHistogram();

    // The average time (in micros) to invoke a request (in RequestCorrelator)
    protected static final Histogram avg_req_time=createHistogram();

    // The average time (in micros) to handle a response (in RequestCorrelator)
    protected static final Histogram avg_rsp_time=createHistogram();

    // The average time (in micros) to handle entire batches of requests or responses
    protected static final Histogram avg_batch_req_time=createHistogram();

    protected static final AverageMinMax avg_batch_size_received=new AverageMinMax();


    // sets and gets microseconds recorded by threads
    protected static final ConcurrentMap<Thread,Long> receive_timings=new ConcurrentHashMap<>();

    protected static final ConcurrentMap<Thread,Long> delivery_timings=new ConcurrentHashMap<>();

    protected static final ConcurrentMap<Thread,Long> req_timings=new ConcurrentHashMap<>();

    protected static final ConcurrentMap<Thread,Long> req_batch_timings=new ConcurrentHashMap<>();

    protected static final ConcurrentMap<Thread,Long> transport_send_time=new ConcurrentHashMap<>();


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

    @SuppressWarnings("MethodMayBeStatic")
    public void recordRequestTime() {
        req_timings.put(Thread.currentThread(), Util.micros());
    }

    @SuppressWarnings("MethodMayBeStatic")
    public long getRequestTime() {
        Long val=req_timings.get(Thread.currentThread());
        return val != null? val : 0;
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void recordRequestBatchTime() {
        req_batch_timings.put(Thread.currentThread(), Util.micros());
    }

    @SuppressWarnings("MethodMayBeStatic")
    public long getRequestBatchTime() {
          return req_batch_timings.get(Thread.currentThread());
      }


    @SuppressWarnings("MethodMayBeStatic")
    public void recordTransportSendTime() {
        transport_send_time.put(Thread.currentThread(), Util.micros());
    }

    @SuppressWarnings("MethodMayBeStatic")
    public long getTransportSendTime() {
            return transport_send_time.get(Thread.currentThread());
        }



    public void channelCreated(JChannel ch) {
        ch.getProtocolStack().getTransport().registerProbeHandler(this);
    }


    @SuppressWarnings("MethodMayBeStatic")
    public void addReceiveTimeTo(Message msg, long time) {
        PerfHeader hdr=new PerfHeader(time, 0);
        msg.putHeader(PROT_ID, hdr);
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void addSendTimeTo(Message msg) {
        PerfHeader hdr=new PerfHeader(0, Util.micros());
        msg.putHeader(PROT_ID, hdr);
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void attachRecordedTimeTo(Message msg) {
        long previously_recorded_time=getReceiveTime();
        if(previously_recorded_time > 0)
            addReceiveTimeTo(msg, previously_recorded_time);
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void attachRecordedTimeTo(MessageBatch[] batches) {
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
    public void computeReceiveTime(Message msg) {
        PerfHeader hdr=msg.getHeader(PROT_ID);
        if(hdr != null && hdr.receive_time > 0) {
            long time=Util.micros() - hdr.receive_time;
            hdr.receive_time=0;
            avg_receive_time.recordValue(time);
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void computeReceiveTime(MessageBatch batch) {
        if(!batch.isEmpty()) {
            Message first=batch.first();
            PerfHeader hdr=first.getHeader(PROT_ID);
            if(hdr != null && hdr.receive_time > 0) {
                long time=Util.micros() - hdr.receive_time;
                hdr.receive_time=0;
                avg_receive_time.recordValue(time);
            }
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void computeResponseTime() {
        long previously_recorded_time=getRequestTime();
        if(previously_recorded_time > 0)
            avg_rsp_time.recordValue(Util.micros() - previously_recorded_time);
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void computeRequestTime() {
        long previously_recorded_time=getRequestTime();
        if(previously_recorded_time > 0)
            avg_req_time.recordValue(Util.micros() - previously_recorded_time);
    }

    @SuppressWarnings("MethodMayBeStatic")
       public void computeRequestBatchTime() {
        long previously_recorded_time=getRequestBatchTime();
        if(previously_recorded_time > 0)
            avg_batch_req_time.recordValue(Util.micros() - previously_recorded_time);
    }


    @SuppressWarnings("MethodMayBeStatic")
    public void computeDownTime(Message msg) {
        PerfHeader hdr=msg != null? msg.getHeader(PROT_ID) : null;
        if(hdr != null && hdr.send_time > 0) {
            long time=Util.micros() - hdr.send_time;
            avg_down_time.recordValue(time);
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void computeSendTime(Message msg) {
        PerfHeader hdr=msg != null? msg.getHeader(PROT_ID) : null;
        if(hdr != null && hdr.send_time > 0) {
            long time=Util.micros() - hdr.send_time;
            hdr.send_time=0; // to prevent multiple computations caused by retransmission
            avg_send_time.recordValue(time);
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void computeSendTime(List<Message> list) {
        if(list != null) {
            long current_time=Util.micros();
            for(Message msg: list) {
                PerfHeader hdr=msg.getHeader(PROT_ID);
                if(hdr != null && hdr.send_time > 0) {
                    long time=current_time - hdr.send_time;
                    hdr.send_time=0; // to prevent multiple computations caused by retransmission
                    avg_send_time.recordValue(time);
                }
            }
        }
    }


    @SuppressWarnings("MethodMayBeStatic")
    public void computeTransportSendTime() {
        long previously_recorded_time=getTransportSendTime();
        if(previously_recorded_time > 0)
            avg_transport_send_time.recordValue(Util.micros() - previously_recorded_time);
    }


    public void afterDelivery() {
        long previously_recorded_time=getDeliveryTime();
        if(previously_recorded_time > 0) {
            long time=Util.micros() - previously_recorded_time;
            avg_delivery_time.recordValue(time);
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
                case "timings-per":
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

    protected static Histogram createHistogram() {
        return new SynchronizedHistogram(1, 80_000_000, 3);
    }

    protected static void reset() {
        avg_receive_time.reset();
        avg_delivery_time.reset();
        avg_down_time.reset();
        avg_send_time.reset();
        avg_transport_send_time.reset();
        avg_req_time.reset();
        avg_rsp_time.reset();
        avg_batch_req_time.reset();
        avg_batch_size_received.clear();
    }

    protected static void addStats(Map<String,String> map, boolean print_details) {
        map.put("avg_receive_time",        print(avg_receive_time, print_details));
        map.put("avg_delivery_time",       print(avg_delivery_time, print_details));
        map.put("avg_down_time",           print(avg_down_time, print_details));
        map.put("avg_send_time",           print(avg_send_time, print_details));
        map.put("avg_tp_send_time",        print(avg_transport_send_time, print_details));
        map.put("avg_req_time",            print(avg_req_time, print_details));
        map.put("avg_rsp_time",            print(avg_rsp_time, print_details));
        map.put("avg_batch_req_time",      print(avg_batch_req_time, print_details));
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

        public PerfHeader reset() {receive_time=send_time=0; return this;}

        public short getMagicId() {
            return ID;
        }

        public Supplier<? extends Header> create() {
            return PerfHeader::new;
        }

        /** We don't serialize PerfHeader as it is used only locally */
        public int serializedSize() {
            return 0;
        }

        public void writeTo(DataOutput out) throws Exception {
        }

        public void readFrom(DataInput in) throws Exception {
        }
    }
}
