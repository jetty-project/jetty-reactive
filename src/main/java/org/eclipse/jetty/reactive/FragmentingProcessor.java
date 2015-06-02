package org.eclipse.jetty.reactive;

import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;



/** 
 * Processor that will split subscribed buffers to a maximum size.
 */
public class FragmentingProcessor implements Processor<ByteBuffer, ByteBuffer>
{
    private final int maxBufferSize;
    private final Semaphore demand;
    private Subscriber<? super ByteBuffer> subscriber;
    private Subscription subscription;
    
    public FragmentingProcessor(int maxBufferSize)
    {
        this.maxBufferSize=maxBufferSize;
        this.demand=new Semaphore(0);
    }
    
    @Override
    public void onSubscribe(Subscription s)
    {
        subscription=s;
        subscription.request(demand.availablePermits());
    }

    @Override
    public void onNext(ByteBuffer t)
    {
        try
        {
            ByteBuffer slice=t.slice();
            int length = slice.remaining();
            int offset = slice.position();

            // TODO This is blocking!  How can it be done asynchronously?
            while (length>0)
            {
                int chunk = Math.min(length,maxBufferSize);
                offset+=chunk;
                length-=chunk;
                slice.limit(offset);

                demand.acquire();
                subscriber.onNext(slice);
                slice.position(offset);
            }
        }
        catch(InterruptedException e)
        {
            subscriber.onError(e);
            subscription.cancel();
        }
    }

    @Override
    public void onError(Throwable t)
    {
        subscriber.onError(t);
    }

    @Override
    public void onComplete()
    {
        subscriber.onComplete();
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> s)
    {
        if (subscriber!=null)
            throw new IllegalStateException();
        subscriber=s;
        subscriber.onSubscribe(new Subscription()
        {
            @Override
            public void request(long n)
            {
                demand.release((int)n);
                if (FragmentingProcessor.this.subscription!=null)
                    FragmentingProcessor.this.subscription.request(n);
            }
            
            @Override
            public void cancel()
            {
                FragmentingProcessor.this.subscription.cancel();
            }
        });
    }
}
