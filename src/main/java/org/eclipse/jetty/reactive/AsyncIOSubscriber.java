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
import java.util.function.BiConsumer;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class AsyncIOSubscriber implements Subscriber<ByteBuffer>, WriteListener
{
    private final AsyncContext context;
    private final BiConsumer<AsyncIOSubscriber, ByteBuffer> consumer;
    private Subscription subscription;
    private boolean pending;

    public AsyncIOSubscriber(AsyncContext context, BiConsumer<AsyncIOSubscriber, ByteBuffer> consumer) throws IOException
    {
        this.context = context;
        this.consumer = consumer;
        HttpServletResponse response = (HttpServletResponse)context.getResponse();
        response.getOutputStream().setWriteListener(this);
    }

    @Override
    public void onSubscribe(Subscription subscription)
    {
        this.subscription = subscription;
        // Upon subscribe, always ready to write.
        subscription.request(1);
    }

    @Override
    public void onNext(ByteBuffer buffer)
    {
        consumer.accept(this, buffer);
    }

    @Override
    public void onComplete()
    {
        context.complete();
    }

    @Override
    public void onError(Throwable failure)
    {
        // TODO
    }

    @Override
    public void onWritePossible() throws IOException
    {
        if (pending)
        {
            pending = false;
            subscription.request(1);
        }
    }

    protected boolean send(ByteBuffer buffer)
    {
        try
        {
            ServletOutputStream output = context.getResponse().getOutputStream();
            output.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
            if (output.isReady())
            {
                buffer.position(buffer.limit());
                subscription.request(1);
                return true;
            }
            else
            {
                pending = true;
                return false;
            }
        }
        catch (IOException failure)
        {
            onError(failure);
            return false;
        }
    }
}
