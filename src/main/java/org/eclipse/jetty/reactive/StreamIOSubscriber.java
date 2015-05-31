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

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class StreamIOSubscriber implements Subscriber<ByteBuffer>
{
    private final AsyncContext context;
    private final BiConsumer<StreamIOSubscriber, ByteBuffer> consumer;
    private Subscription subscription;

    public StreamIOSubscriber(AsyncContext context, BiConsumer<StreamIOSubscriber, ByteBuffer> consumer)
    {
        this.context = context;
        this.consumer = consumer;
    }

    @Override
    public void onSubscribe(Subscription subscription)
    {
        this.subscription = subscription;
        // OutputStream is always ready to write
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

    protected void send(ByteBuffer buffer)
    {
        try
        {
            ServletOutputStream output = context.getResponse().getOutputStream();
            output.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
            subscription.request(1);
        }
        catch (Throwable failure)
        {
            onError(failure);
        }
    }
}
