package org.eclipse.jetty.reactive;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.IdentityProcessorVerification;
import org.reactivestreams.tck.TestEnvironment;

public class IteratingProcessorTest extends IdentityProcessorVerification<Integer> 
{
    public static final long DEFAULT_TIMEOUT_MILLIS = 300L;
    public static final long PUBLISHER_REFERENCE_CLEANUP_TIMEOUT_MILLIS = 1000L;

    public static class MyIdentityProcessor extends IteratingProcessor<Integer,Integer>
    {
        @Override
        protected Integer process(Integer item)
        {
            return item;
        }        
    }

    public IteratingProcessorTest() 
    {
      super(new TestEnvironment(DEFAULT_TIMEOUT_MILLIS), PUBLISHER_REFERENCE_CLEANUP_TIMEOUT_MILLIS);
    }

    @Override
    public Processor<Integer, Integer> createIdentityProcessor(int bufferSize) 
    {
      return new MyIdentityProcessor();
    }

    @Override
    public Publisher<Integer> createFailedPublisher()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Integer createElement(int item)
    {
        return new Integer(item);
    }

    @Override
    public ExecutorService publisherExecutorService()
    {
        return new ThreadPoolExecutor(10,10,10,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(1000));
    }


  }
