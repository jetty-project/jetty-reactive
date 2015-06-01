package org.eclipse.jetty.reactive;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class StdErrSubscriber implements Subscriber<ByteBuffer>
{
    private final int max;
    private final AtomicInteger window = new AtomicInteger();
    private final Scheduler scheduler;
    private Subscription subscription;
    
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
    public void onNext(ByteBuffer t)
    {
        System.err.println(BufferUtil.toDetailString(t));
        
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
        t.printStackTrace();
    }

    @Override
    public void onComplete()
    {
        System.err.println("===");
    }

}
