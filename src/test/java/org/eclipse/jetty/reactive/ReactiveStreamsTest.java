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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class ReactiveStreamsTest
{
    @Rule
    public TestTracker tracker = new TestTracker();

    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void prepareServer(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        context.addServlet(new ServletHolder(servlet), "/*");

        server.start();
    }

    @Before
    public void prepareClient() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient();
        client.setExecutor(clientThreads);
        client.start();
    }

    @After
    public void dispose() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testBlockingIO() throws Exception
    {
        prepareServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext context = request.startAsync();
                Publisher<ByteBuffer> publisher = ReactiveSupport.getPublisher(context);
                publisher.subscribe(new StreamIOSubscriber(context, (subscriber, buffer) -> subscriber.send(buffer)));
            }
        });

        byte[] bytes = new byte[512];
        new Random().nextBytes(bytes);
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .content(new BytesContentProvider(bytes))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testAsyncIO() throws Exception
    {
        prepareServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext context = request.startAsync();
                Publisher<ByteBuffer> publisher = ReactiveSupport.getPublisher(context);
                publisher.subscribe(new AsyncIOSubscriber(context, AsyncIOSubscriber::send));
            }
        });

        byte[] bytes = new byte[1024 * 1024];
        new Random().nextBytes(bytes);
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .content(new BytesContentProvider(bytes));
        FutureResponseListener listener = new FutureResponseListener(request, bytes.length);
        request.send(listener);
        ContentResponse response = listener.get();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testSlowAsyncIO() throws Exception
    {
        prepareServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext context = request.startAsync();
                Publisher<ByteBuffer> publisher = ReactiveSupport.getPublisher(context);
                publisher.subscribe(new AsyncIOSubscriber(context, AsyncIOSubscriber::send));
            }
        });

        byte[] bytes = new byte[1024 * 1024];
        new Random().nextBytes(bytes);
        DeferredContentProvider content = new DeferredContentProvider();
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.GET)
                .content(content);
        FutureResponseListener listener = new FutureResponseListener(request, bytes.length);
        request.send(listener);

        int chunk = bytes.length / 8;
        int offset = 0;
        while (offset < bytes.length)
        {
            int length = Math.min(bytes.length - offset, chunk);
            content.offer(ByteBuffer.wrap(bytes, offset, length));
            offset += length;
            Thread.sleep(500);
        }
        content.close();

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testAsyncForm() throws Exception
    {
        Fields requestFields = new Fields(true);
        requestFields.put("a", "1");
        requestFields.put("b", "c");

        prepareServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext context = request.startAsync();
                Publisher<ByteBuffer> publisher = ReactiveSupport.getPublisher(context);
                Processor<ByteBuffer, Fields> processor = new FormProcessor();
                publisher.subscribe(processor);
                final CountDownLatch latch = new CountDownLatch(1);
                processor.subscribe(new Subscriber<Fields>()
                {
                    @Override
                    public void onSubscribe(Subscription subscription)
                    {
                        subscription.request(1);
                    }

                    @Override
                    public void onNext(Fields fields)
                    {
                        Assert.assertEquals(requestFields, fields);
                        latch.countDown();
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
                });
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .content(new FormContentProvider(requestFields))
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }
    

    @Test
    public void testAsyncFormFields() throws Exception
    {
        Fields requestFields = new Fields(true);
        requestFields.put("a", "1");
        requestFields.put("bbb", "two");
        requestFields.put("blah", "blah");

        Fields resultFields = new Fields(true);

        prepareServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext context = request.startAsync();
                Publisher<ByteBuffer> publisher = ReactiveSupport.getPublisher(context);
                Processor<ByteBuffer, Fields.Field> processor = new FormFieldProcessor();
                publisher.subscribe(processor);
                processor.subscribe(new Subscriber<Fields.Field>()
                {
                    Subscription subscription;
                    @Override
                    public void onSubscribe(Subscription a)
                    {
                        subscription=a;
                        subscription.request(1);
                    }

                    @Override
                    public void onNext(Fields.Field field)
                    {
                        resultFields.add(field.getName(),field.getValue());
                        subscription.request(1);
                    }

                    @Override
                    public void onComplete()
                    {
                        context.complete();
                    }

                    @Override
                    public void onError(Throwable failure)
                    {
                        resultFields.clear();
                        failure.printStackTrace();
                    }
                });
            }
            

        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .content(new FormContentProvider(requestFields))
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertEquals(requestFields, resultFields);
        
        
    }
}
