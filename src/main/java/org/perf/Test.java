package org.perf;


import org.infinispan.Cache;
import org.infinispan.context.Flag;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.Transport;
import org.jgroups.util.Util;

import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class Test {
    protected EmbeddedCacheManager   mgr;
    protected Cache<Integer,byte[]>  cache;
    protected TransactionManager     txmgr;
    protected Address                local_addr;
    protected boolean                sync=false, use_txs=false;
    protected int                    num_threads=1;
    protected int                    num_rpcs=10000, msg_size=1000, print=num_rpcs / 10;
    protected final AtomicInteger    num_requests=new AtomicInteger(0);



    protected void start() throws Exception {
        mgr=new DefaultCacheManager("infinispan.xml");
        cache=mgr.getCache("clusteredCache");
        txmgr=cache.getAdvancedCache().getTransactionManager();
        local_addr=cache.getAdvancedCache().getRpcManager().getAddress();

        if(!cache.isEmpty()) {
            int size=cache.size();
            if(size < 10)
                System.out.println("cache already contains elements: " + cache.keySet());
            else
                System.out.println("cache already contains " + size + " elements");
        }
        try {
            eventLoop();
        }
        catch(Throwable t) {
            t.printStackTrace();
        }
        cache.stop();
        mgr.stop();
    }


    public void eventLoop() throws Throwable {
        int c;

        while(true) {
            System.out.print("[1] Invoke RPCs [2] Print view [3] Set sender threads (" + num_threads +
                               ") [4] Set num RPCs (" + num_rpcs + ") " +
                               "\n[5] Set msg size (" + Util.printBytes(msg_size) + ")" +
                               " [6] Print cache size [7] Print contents [8] Clear cache" +
                               "\n[t] Toggle TXs (" + use_txs + ") [s] Toggle sync (" + sync + ")" +
                               "\n[q] Quit\n");
            System.out.flush();
            c=System.in.read();
            switch(c) {
                case -1:
                    break;
                case '1':
                    try {
                        invokeRpcs();
                    }
                    catch(Throwable t) {
                        System.err.println(t);
                    }
                    break;
                case '2':
                    printView();
                    break;
                case '3':
                    setSenderThreads();
                    break;
                case '4':
                    setNumMessages();
                    break;
                case '5':
                    setMessageSize();
                    break;
                case '6':
                    printCacheSize();
                    break;
                case '7':
                    printContents();
                    break;
                case '8':
                    clearCache();
                    break;
                case 't':
                    use_txs=!use_txs;
                    System.out.println("TXs=" + use_txs);
                    break;
                case 's':
                    sync=!sync;
                    System.out.println("sync=" + sync);
                    break;
                case 'q': case'x':
                    return;
                default:
                    break;
            }
        }
    }


    protected void invokeRpcs() throws Throwable {
        num_requests.set(0);

        System.out.println("invoking " + num_rpcs + " RPCs of " + Util.printBytes(msg_size) + ", sync=" + sync + ", use TXs=" + use_txs);

        // The first call needs to be synchronous with OOB !
        final CountDownLatch latch=new CountDownLatch(1);
        Invoker[] invokers=new Invoker[num_threads];
        for(int i=0; i < invokers.length; i++) {
            invokers[i]=new Invoker(latch);
            invokers[i].setName("invoker-" + i);
            invokers[i].start();
        }

        long start=System.currentTimeMillis();
        latch.countDown();

        for(Invoker invoker: invokers)
            invoker.join();
        long time=System.currentTimeMillis() - start;

        System.out.println("done invoking " + num_rpcs + " RPCs");

        double time_per_req=time / (double)num_rpcs;
        double reqs_sec=num_rpcs / (time / 1000.0);
        double throughput=num_rpcs * msg_size / (time / 1000.0);
        System.out.println(Util.bold("\ninvoked " + num_rpcs + " requests in " + time + " ms: " + time_per_req + " ms/req, " +
                                       String.format("%.2f", reqs_sec) + " reqs/sec, " + Util.printBytes(throughput) + "/sec\n"));
    }


    protected void printView() {
        Transport transport=cache.getAdvancedCache().getRpcManager().getTransport();
        int view_id=transport.getViewId();
        List<Address> members=transport.getMembers();
        String view=view_id + "|" + members;
        System.out.println("\n-- view: " + view + '\n');
        try {
            System.in.skip(System.in.available());
        }
        catch(Exception e) {
        }
    }

    protected void printCacheSize() {
        int size=cache.size();
        System.out.println("-- cache has " + size + " elements");
    }

    protected void printContents() {
        int size=cache.size();
        if(size < 50)
            System.out.println(cache.keySet());
        else
            System.out.println(size + " elements");
    }

    protected void clearCache() {
        cache.clear();
    }

    protected void setSenderThreads() throws Exception {
        int threads=Util.readIntFromStdin("Number of sender threads: ");
        int old=this.num_threads;
        this.num_threads=threads;
        System.out.println("sender threads set to " + num_threads + " (from " + old + ")");
    }

    protected void setNumMessages() throws Exception {
        num_rpcs=Util.readIntFromStdin("Number of RPCs: ");
        System.out.println("Set num_msgs=" + num_rpcs);
        print=num_rpcs / 10;
    }

    protected void setMessageSize() throws Exception {
        msg_size=Util.readIntFromStdin("Message size: ");
        System.out.println("set msg_size=" + msg_size);
    }


    protected class Invoker extends Thread {
        private final CountDownLatch latch;


        public Invoker(CountDownLatch latch) {
            this.latch=latch;
        }

        public void run() {
            byte[] buf=new byte[msg_size];
            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
                return;
            }

            for(;;) {
                int i=num_requests.incrementAndGet();
                if(i > num_rpcs)
                    break;


                Transaction tx=null;
                try {
                    if(use_txs) {
                        txmgr.begin();
                        tx=txmgr.getTransaction();
                    }

                    Flag[] flags=sync? new Flag[]{Flag.IGNORE_RETURN_VALUES, Flag.SKIP_REMOTE_LOOKUP, Flag.FORCE_SYNCHRONOUS} :
                      new Flag[]{Flag.IGNORE_RETURN_VALUES, Flag.SKIP_REMOTE_LOOKUP, Flag.FORCE_ASYNCHRONOUS};
                    cache.getAdvancedCache().withFlags(flags).put(i, buf);

                    if(print > 0 && i % print == 0)
                        System.out.println("-- invoked " + i);
                    if(tx != null)
                        tx.commit();
                }
                catch(Throwable t) {
                    t.printStackTrace();
                    if(tx != null) {
                        try {
                            tx.rollback();
                        }
                        catch(SystemException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }


    public static void main(String[] args) throws Exception {
        Test test=new Test();
        test.start();
    }

}