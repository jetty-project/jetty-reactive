package org.eclipse.jetty.reactive;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class StdErrSubscriber implements Subscriber<byte[]>
{
    private final int max;
    private final AtomicInteger window = new AtomicInteger();
    private final Scheduler scheduler;
    private Subscription subscription;
    private CountDownLatch complete = new CountDownLatch(1);
    
    StdErrSubscriber(Scheduler s, int window)
    {
        this.max=window;
        scheduler=s;
    }
    
    @Override
    public void onSubscribe(Subscription s)
    {
        System.err.println("---");
        subscription=s;
        subscription.request(max);
        window.set(max);
    }

    @Override
    public void onNext(byte[] item)
    {
        System.err.println(BufferUtil.toDetailString(BufferUtil.toBuffer(item)));
        
        int w=window.decrementAndGet();
        while (w<=(max/2))
        {
            if (window.compareAndSet(w,max))
            {
                final int increment = max-w;
                // schedule request to simulate latency
                scheduler.schedule(()->{ subscription.request(increment);},10,TimeUnit.MILLISECONDS);
                break;
            }
            w=window.get();
        }    
    }

    @Override
    public void onError(Throwable t)
    {
        complete.countDown();
        t.printStackTrace();
    }

    @Override
    public void onComplete()
    {
        complete.countDown();
        System.err.println("===");
    }
    
    public void waitUntilComplete() throws InterruptedException
    {
        complete.await();
    }

}
