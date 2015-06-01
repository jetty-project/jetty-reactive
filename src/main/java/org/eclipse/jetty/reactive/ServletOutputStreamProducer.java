package org.eclipse.jetty.reactive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.IllegalSelectorException;
import java.nio.channels.WritePendingException;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.SpinLock;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;


public class ServletOutputStreamProducer extends ServletOutputStream implements Publisher<ByteBuffer>
{
    private final SpinLock lock= new SpinLock();
    private WriteListener listener;
    private boolean unready;
    private Subscriber<? super ByteBuffer> subscriber;
    private int demand;
    
    @Override
    public void subscribe(Subscriber<? super ByteBuffer> s)
    {
        subscriber=s;
        s.onSubscribe(new Subscription()
        {
            @Override
            public void request(long n)
            {
                boolean possible=false;
                try(SpinLock.Lock l=lock.lock())
                {
                    possible = unready && demand==0 && n>0;
                    if (possible)
                        unready=false;
                    demand+=n;
                }
                
                if (possible)
                {
                    try
                    {
                        listener.onWritePossible();
                    }
                    catch(Exception e)
                    {
                        listener.onError(e);
                    }
                }
            }
            
            @Override
            public void cancel()
            {
                try(SpinLock.Lock l=lock.lock())
                {
                    demand=0;
                    unready=false;
                }
                listener.onError(new IOException("cancelled"));
            }
        });
    }

    @Override
    public boolean isReady()
    {
        try(SpinLock.Lock l=lock.lock())
        {
            boolean ready=demand>0;
            if (!ready)
                unready=true;
            return ready;
        }
    }

    @Override
    public void setWriteListener(WriteListener writeListener)
    {
        boolean possible=false;
        try(SpinLock.Lock l=lock.lock())
        {
            if (listener!=null)
                throw new IllegalSelectorException();
            listener=writeListener;
            possible=demand>0;
        }
        if (possible)
        {
            try
            {
                listener.onWritePossible();
            }
            catch(Exception e)
            {
                listener.onError(e);
            }
        }
    }

    @Override
    public void write(int b) throws IOException
    {
        write(new byte[]{(byte)(0xff&b)},0,1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        ByteBuffer buffer = BufferUtil.toBuffer(b,off,len);
        try(SpinLock.Lock l=lock.lock())
        {
            if (demand==0)
                throw new WritePendingException();
            demand--;
        }
        subscriber.onNext(buffer);
    }

    @Override
    public void close() throws IOException
    {
        subscriber.onComplete();
        super.close();
    }
}
