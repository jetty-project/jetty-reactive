//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.reactive;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class RequestPublisher implements Publisher<ByteBuffer>, Subscription, ReadListener
{
    private static final Logger LOG = Log.getLogger(RequestPublisher.class);

    private final AsyncContext context;
    private final byte[] bytes;
    private final ByteBuffer buffer;
    private Subscriber<? super ByteBuffer> subscriber;
    private long demand;
    private boolean stalled;

    public RequestPublisher(AsyncContext context, int bufferSize)
    {
        this.context = context;
        this.bytes = new byte[bufferSize];
        this.buffer = ByteBuffer.wrap(bytes);
    }

    @Override
    public void onDataAvailable() throws IOException
    {
        ServletInputStream input = context.getRequest().getInputStream();
        if (LOG.isDebugEnabled())
            LOG.debug("ODA {}", input);

        while (true)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Demand: {}", demand);

            if (demand <= 0)
            {
                stalled = true;
                break;
            }

            boolean ready = input.isReady();
            if (LOG.isDebugEnabled())
                LOG.debug("Input ready: {}/{}", ready, input.isFinished());

            if (!ready)
                break;

            int read = input.read(bytes);
            if (LOG.isDebugEnabled())
                LOG.debug("Input read: {}", read);

            if (read < 0)
                break;

            if (read > 0)
            {
                --demand;
                buffer.position(0);
                buffer.limit(read);
                if (LOG.isDebugEnabled())
                    LOG.debug("Next: {}", buffer);
                subscriber.onNext(buffer);
            }
        }
    }

    @Override
    public void onAllDataRead() throws IOException
    {
        subscriber.onComplete();
    }

    @Override
    public void onError(Throwable failure)
    {
        subscriber.onError(failure);
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber)
    {
        if (this.subscriber != null)
            throw new IllegalStateException();
        this.subscriber = subscriber;

        subscriber.onSubscribe(this);
    }

    private void notifyDataAvailable()
    {
        try
        {
            onDataAvailable();
        }
        catch (Throwable failure)
        {
            onError(failure);
        }
    }

    @Override
    public void request(long n)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Updating demand {} by {}, stalled: {}", demand, n, stalled);
        demand += n;
        if (stalled)
        {
            stalled = false;
            notifyDataAvailable();
        }
    }

    @Override
    public void cancel()
    {
        // TODO more than this
        try
        {
            context.getRequest().getInputStream().close();
        }
        catch (IOException e)
        {
            LOG.warn("Cancel could not close",e);
        }   
    }
}
