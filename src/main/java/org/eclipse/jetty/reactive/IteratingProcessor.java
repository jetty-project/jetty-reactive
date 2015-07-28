package org.eclipse.jetty.reactive;

import java.util.ArrayDeque;
import java.util.Deque;

import org.eclipse.jetty.util.thread.Locker;
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
    private final Locker lock = new Locker();
    private final Deque<T> queue = new ArrayDeque<>();
    private Subscription publisher;
    private Subscriber<? super R> subscriber;
    private long requests;
    private long requested;
    private boolean complete;
    private boolean iterating;

    @Override
    public void onSubscribe(Subscription s)
    {
        if (s==null)
            throw new NullPointerException();
        boolean connect=false;
        try(Locker.Lock l = lock.lock();)
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
        if (item==null)
            throw new NullPointerException();
        boolean iterate;
        try(Locker.Lock l = lock.lock();)
        {
            // Sanity checks
            if (complete)
                return;
            if (requested==0)
            {
                publisher.cancel();
                subscriber.onError(new IllegalStateException("unrequested item"));
                return;
            }
            
            requested--;
            queue.add(item);
            
            // If somebody is already iterating (could be this thread in a higher stack frame),
            // then don't re-enter iterate() here
            iterate = !iterating && requests>0;
            iterating |= iterate;
        }
        
        if (iterate)
            iterate();
    }

    @Override
    public void onError(Throwable t)
    {
        boolean onError=false;
        try(Locker.Lock l = lock.lock();)
        {
            onError=!complete;
            complete=true;
            requests=0;
            requested=0;
            queue.clear();
        }
        if (onError)
            subscriber.onError(t);
    }

    @Override
    public void onComplete()
    {
        boolean iterate;
        try(Locker.Lock l = lock.lock();)
        {
            if (complete)
                return;
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
        if (s==null)
            throw new NullPointerException();
        boolean connect=false;
        try(Locker.Lock l = lock.lock();)
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
                boolean iterate;
                long demand;
                try(Locker.Lock l = lock.lock();)
                {
                    requests+=n;
                    iterate = !iterating && requests>0 && (complete || !queue.isEmpty());
                    iterating |= iterate;
                    
                    demand=(iterating || !queue.isEmpty())?0:(requests-requested);
                    requested+=demand;
                }
                if (iterate)
                    iterate();
                else if (demand>0)
                    publisher.request(demand);
            }
            
            @Override
            public void cancel()
            {
                boolean cancel=false;
                try(Locker.Lock l = lock.lock();) 
                {
                    cancel=!complete;
                    complete=true;
                    requests=0;
                    requested=0;
                    queue.clear();
                }
                if (cancel)
                    publisher.cancel();
            }
        });
    }
    
    /** Produce an R result from a T item.
     * @param item The item to process a result from, or null of complete is true
     * @return the results
     */
    protected abstract R process(T item);

    protected R complete()
    {
        return null;
    }
    
    protected boolean isConsumed(T item)
    {
        return true;
    }

    private void iterate()
    {
        boolean consumed=false;
        while(true)
        {
            T item;
            try(Locker.Lock l = lock.lock();)
            {
                iterating = requests>0 && (complete || !queue.isEmpty());
                if (!iterating)
                    break;
                item=queue.peek();
            }
            
            R result;
            
            if (item==null)
            {
                result=complete();
                consumed=false;
            }
            else
            {
                result=process(item);
                consumed=isConsumed(item);
            }
            
            long demand=0;
            try(Locker.Lock l = lock.lock();)
            {
                if (consumed)
                    queue.pop();
                
                if (result!=null)
                {
                    requests--;
                }
                else if (queue.isEmpty())
                {
                    demand=complete?0:(requests-requested);
                    requested+=demand;
                    iterating=!complete && demand<=0;
                }
                else
                    demand=-1;
            }
            
            // If we can keep processing call somebody
            if (result!=null)
                subscriber.onNext(result); // may callback request(n)
            else if (demand>0)
                publisher.request(demand); // may callback onNext(item)
            else if (demand==0)
            {
                if (complete)
                    subscriber.onComplete();
                break;
            }

        }
    }
    
}
