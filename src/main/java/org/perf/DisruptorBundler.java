package org.perf;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.jgroups.Address;
import org.jgroups.Message;
import org.jgroups.protocols.BaseBundler;
import org.jgroups.protocols.Bundler;
import org.jgroups.protocols.RingBufferBundler;
import org.jgroups.protocols.TP;
import org.jgroups.util.DefaultThreadFactory;
import org.jgroups.util.Util;

import java.util.Objects;

/**
 * Implementation of a {@link org.jgroups.protocols.Bundler} with LMAX's Disruptor. To use it in JGroups, either
 * set bundler_type="org.perf.DisruptorBundler" in TP (UDP or TCP), or change it dynamically via probe.sh:<p/>
 * probe.sh -cluster default op=UDP.bundler["org.perf.DisruptorBundler"]<p/>
 * Note that this impl works against 3.6.x, and hasn't been tested against 4.0.
 * @author Bela Ban
 */
@SuppressWarnings("unused")
public class DisruptorBundler extends BaseBundler implements EventHandler<DisruptorBundler.MessageEvent> {
    protected Disruptor<MessageEvent>   disruptor;
    protected RingBuffer<MessageEvent>  buf;

    protected static final int          MSG_BUF_SIZE=512;
    protected final Message[]           msg_queue=new Message[MSG_BUF_SIZE];
    protected int                       curr;

    // <-- change this to experiment with different wait strategies
    protected WaitStrategy              strategy=new SleepingWaitStrategy(); // fastest but high CPU
    // strategy=new YieldingWaitStrategy(); // ditto
    // strategy=new BusySpinWaitStrategy();
    // protected final WaitStrategy strategy=new BlockingWaitStrategy();


    public DisruptorBundler() {

    }

    public DisruptorBundler(int capacity) {
        disruptor=new Disruptor<>(new MessageEventFactory(), capacity, new DefaultThreadFactory("disruptor", false, true),
                                  ProducerType.MULTI, strategy);
        disruptor.handleEventsWith(this);
    }

    public int size() {return buf.getBufferSize();}

    public void init(TP transport) {
        super.init(transport);

        Bundler bundler=transport.getBundler();
        String tmp=bundler instanceof RingBufferBundler? ((RingBufferBundler)bundler).waitStrategy() : "park";
        strategy=createStrategy(tmp);

        disruptor=new Disruptor<>(new MessageEventFactory(), bundler.getCapacity(), new DefaultThreadFactory("disruptor", false, true),
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
        long size=msg.size();
        if(count + size >= transport.getBundler().getMaxSize())
            sendBundledMessages();
        addMessage(msg, size);
        if(endOfBatch)
            sendBundledMessages(); // else wait for the next event
    }

    protected static WaitStrategy createStrategy(String name) {
        if(name != null) {
            switch(name) {
                case "SleepingWaitStrategy": case "sleep-wait":
                    return new SleepingWaitStrategy();
                case "YieldingWaitStrategy": case "yield":
                    return new YieldingWaitStrategy();
                case "BusySpinWaitStrategy": case "busy-spin":
                    return new BusySpinWaitStrategy();
                case "BlockingWaitStrategy": case "blocking-wait":
                    return new BlockingWaitStrategy();
            }
        }
        return new SleepingWaitStrategy();
    }

    protected void addMessage(Message msg, long size) {
        try {
            while(curr < MSG_BUF_SIZE && msg_queue[curr] != null) ++curr;
            if(curr < MSG_BUF_SIZE) {
                msg_queue[curr]=msg;
                ++curr;
            }
            else {
                sendBundledMessages(); // sets curr to 0
                msg_queue[0]=msg;
            }
        }
        finally {
            count+=size;
        }
    }


    protected void sendBundledMessages() {
        try {
            _sendBundledMessages();
        }
        finally {
            curr=0;
        }
    }

    protected void _sendBundledMessages() {
        int start=0;
        for(;;) {
            for(; start < MSG_BUF_SIZE && msg_queue[start] == null; ++start) ;
            if(start >= MSG_BUF_SIZE) {
                count=0;
                return;
            }
            Address dest=msg_queue[start].getDest();
            int numMsgs=1;
            for(int i=start + 1; i < MSG_BUF_SIZE; ++i) {
                Message msg=msg_queue[i];
                if(msg != null && (dest == msg.getDest() || (Objects.equals(dest, msg.getDest())))) {
                    msg.setDest(dest); // avoid further equals() calls
                    numMsgs++;
                }
            }
            try {
                output.position(0);
                if(numMsgs == 1) {
                    sendSingleMessage(msg_queue[start]);
                    msg_queue[start]=null;
                }
                else {
                    Util.writeMessageListHeader(dest, msg_queue[start].getSrc(), transport.getClusterNameAscii().chars(), numMsgs, output, dest == null);
                    for(int i=start; i < MSG_BUF_SIZE; ++i) {
                        Message msg=msg_queue[i];
                        // since we assigned the matching destination we can do plain ==
                        if(msg != null && msg.getDest() == dest) {
                            msg.writeToNoAddrs(msg.getSrc(), output, transport.getId());
                            msg_queue[i]=null;
                        }
                    }
                    transport.doSend(output.buffer(), 0, output.position(), dest);
                }
                start++;
            }
            catch(Exception e) {
                log.error("Failed to send message", e);
            }
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
