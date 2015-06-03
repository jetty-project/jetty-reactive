package org.eclipse.jetty.reactive;

import org.eclipse.jetty.util.thread.SpinLock;
import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;



/** 
 * Abstract Processor that iteratively handles processing.
 * <p>This Processor implements a pattern that avoid recursion and re-entrancy when
 * producing 1 or more R results from a single T item.  Typically a call to
 * {@link Subscription#request(long)} may callback on {@link Subscriber#onNext(Object)},
 * which may in turn callback {@link Subscription#request(long)}, which could cause
 * arbitrarily deep recursion.  This process unfolds this recursion into iteration
 * by using state to determine if a thread is already iterating and defers further 
 * processing to that iteration.
 * <p>
 * The Processor also defers a call to its subscribers onSubsription until it also
 * has a {@link Subscription} from its publisher.
 * <p>
 * Completion propagation is also deferred until any previous item is completely
 * processed
 * </p>
 * 
 */
public abstract class IteratingProcessor<T,R> implements Processor<T,R>
{
    private final SpinLock lock = new SpinLock();
    private Subscription publisher;
    private Subscriber<? super R> subscriber;
    private long requests;
    private T next;
    private boolean complete;
    private boolean iterating;

    @Override
    public void onSubscribe(Subscription s)
    {
        boolean connect=false;
        try(SpinLock.Lock l = lock.lock();)
        {
            publisher=s;
            connect=subscriber!=null;
        }
        if (connect)
            connect();
    }

    @Override
    public void onNext(T item)
    {
        boolean iterate;
        try(SpinLock.Lock l = lock.lock();)
        {
            // Sanity checks
            if (requests==0)
                throw new IllegalStateException("unrequested item");
            if (next!=null)
                throw new IllegalStateException("item pending");
            if (complete)
                throw new IllegalStateException("completed");
            
            next=item;
            
            // If somebody is already iterating (could be this thread in a higher stack frame),
            // then don't re-enter iterate() here
            iterate = !iterating && next!=null && requests>0;
            iterating |= iterate;
        }
        
        if (iterate)
            iterate();
    }

    @Override
    public void onError(Throwable t)
    {
        // TODO
    }

    @Override
    public void onComplete()
    {
        boolean iterate;
        try(SpinLock.Lock l = lock.lock();)
        {
            if (complete)
                throw new IllegalStateException("completed");
            complete=true;
            iterate = !iterating && requests>0;
            iterating |= iterate;
        }

        if (iterate)
            iterate();
    }

    @Override
    public void subscribe(Subscriber<? super R> s)
    {
        boolean connect=false;
        try(SpinLock.Lock l = lock.lock();)
        {
            if (subscriber!=null)
                throw new IllegalStateException("already subscribed");
            subscriber=s;
            connect=publisher!=null;
        }

        if (connect)
            connect();
    }
    
    private void connect()
    {
        subscriber.onSubscribe(new Subscription()
        {
            @Override
            public void request(long n)
            {
                boolean process;
                boolean demand;
                try(SpinLock.Lock l = lock.lock();)
                {
                    requests+=n;
                    process = !iterating && requests>0 && (complete || next!=null);
                    iterating |= process;
                    demand=!iterating && next==null && !complete && requests>0;
                }
                if (process)
                    iterate();
                else if (demand)
                    publisher.request(1);
            }
            
            @Override
            public void cancel()
            {
                // TODO
            }
        });
    }
    
    /** Produce an R result from a T item.
     * @param item The item to process a result from, or null of complete is true
     * @param complete True if this is the last item (or item could be null)
     * @return the results
     */
    protected abstract R process(T item,boolean complete);

    private void iterate()
    {
        while(true)
        {
            // Get the next chunk of work
            R result = process(next,complete);
            
            // Did we get something?
            boolean demand=false;
            try(SpinLock.Lock l = lock.lock();)
            {
                if (result==null)
                {
                    next=null;
                    demand=!complete && requests>0;
                    iterating=!complete && !demand;
                }
                else
                    requests--;
            }
            
            // If we can keep processing call somebody
            if (result!=null)
                subscriber.onNext(result); // may callback request(n)
            else if (demand)
                publisher.request(1); // may callback onNext(item)
            else
            {
                if (complete)
                    subscriber.onComplete();
                break;
            }

            // Look to see if we can keep process (may include results from callbacks occured)
            try(SpinLock.Lock l = lock.lock();)
            {
                iterating = requests>0 && (complete || next!=null);
                if (!iterating)
                    break;
            }
        }
    }
    
}
