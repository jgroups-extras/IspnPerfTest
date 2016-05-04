package org.perf;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.jgroups.Message;
import org.jgroups.protocols.BaseBundler;
import org.jgroups.protocols.TP;
import org.jgroups.util.DefaultThreadFactory;

/**
 * Implementation of a {@link org.jgroups.protocols.Bundler} with LMAX's Disruptor. To use it in JGroups, either
 * set bundler_type="org.perf.DisruptorBundler" in TP (UDP or TCP), or change it dynamically via probe.sh:<p/>
 * probe.sh -cluster default op=UDP.bundler["org.perf.DisruptorBundler"]<p/>
 * Note that this impl works against 3.6.x, and hasn't been tested against 4.0.
 * @author Bela Ban
 */
public class DisruptorBundler extends BaseBundler implements EventHandler<DisruptorBundler.MessageEvent> {
    protected Disruptor<MessageEvent>                     disruptor;
    protected com.lmax.disruptor.RingBuffer<MessageEvent> buf;

    // <-- change this to experiment with different wait strategies
    // strategy=new SleepingWaitStrategy(); // fastest but high CPU
    // strategy=new YieldingWaitStrategy(); // ditto
    // strategy=new BusySpinWaitStrategy();
    protected final WaitStrategy                          strategy=new BlockingWaitStrategy();


    public DisruptorBundler() {

    }

    public DisruptorBundler(int capacity) {



        disruptor=new Disruptor<>(new MessageEventFactory(), capacity, new DefaultThreadFactory("disruptor", false, true),
                                  ProducerType.MULTI, strategy);
        disruptor.handleEventsWith(this);
    }

    public int getBufferSize() {return buf.getBufferSize();}

    public void init(TP transport) {
        super.init(transport);
        disruptor=new Disruptor<>(new MessageEventFactory(), transport.getBundlerCapacity(), new DefaultThreadFactory("disruptor", false, true),
                                  ProducerType.MULTI, strategy);
        disruptor.handleEventsWith(this);
    }

    public synchronized void start() {
        disruptor.start();
        buf=disruptor.getRingBuffer();
    }

    public synchronized void stop() {
        disruptor.shutdown();
    }

    public void send(Message msg) throws Exception {
        long seqno=buf.next();
        try {
            MessageEvent event=buf.get(seqno);
            event.set(msg);
        }
        finally {
            buf.publish(seqno);
        }
    }

    public void onEvent(MessageEvent event, long sequence, boolean endOfBatch) throws Exception {
        Message msg=event.msg;
        try {
            long size=msg.size();
            if(count + size >= transport.getMaxBundleSize())
                sendBundledMessages();
            addMessage(msg, size);
            if(endOfBatch)
                sendBundledMessages(); // else wait for the next event
        }
        catch(Throwable t) {
        }
    }

    protected static class MessageEvent {
        protected Message msg;
        protected void set(Message msg) {
            this.msg=msg;
        }
    }

    protected static class MessageEventFactory implements EventFactory<MessageEvent> {

        public MessageEvent newInstance() {
            return new MessageEvent();
        }
    }
}
