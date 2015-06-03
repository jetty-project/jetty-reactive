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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.client.GZIPContentDecoder;
import org.junit.Assert;
import org.junit.Test;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;


public class Base64CompressedStringProcessorTest
{
    @Test
    public void testBase64CompressedStringProcessor() throws Exception
    {
        // The initial bytes.
        byte[] input = new byte[1024];
        Arrays.fill(input, (byte)'x');

        // Create the string.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos))
        {
            gzip.write(input);
        }
        String string = new String(Base64.getEncoder().encode(baos.toByteArray()), StandardCharsets.US_ASCII);

        // Setup the stages.
        Base64CompressedStringPublisher publisher = new Base64CompressedStringPublisher(string);
        Base64CompressedStringProcessor processor = new Base64CompressedStringProcessor();
        publisher.subscribe(processor);
        baos.reset();
        CountDownLatch latch = new CountDownLatch(1);
        processor.subscribe(new Subscriber<ByteBuffer>()
        {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription)
            {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBuffer buffer)
            {
                System.err.println("r: " + buffer.remaining());
                try
                {
                    // Collect the data just to check the bytes are correct.
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    baos.write(bytes);
                    subscription.request(1);
                }
                catch (IOException x)
                {
                    onError(x);
                }
            }

            @Override
            public void onComplete()
            {
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable)
            {
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertArrayEquals(input, baos.toByteArray());
    }

    private class Base64CompressedStringPublisher implements Publisher<String>, Subscription
    {
        private final String string;
        private Subscriber<? super String> subscriber;
        private boolean consumed;

        public Base64CompressedStringPublisher(String string)
        {
            this.string = string;
        }

        @Override
        public void subscribe(Subscriber<? super String> subscriber)
        {
            this.subscriber = subscriber;
            subscriber.onSubscribe(this);
        }

        @Override
        public void request(long items)
        {
            if (!consumed)
            {
                consumed = true;
                subscriber.onNext(string);
            }
            else
            {
                subscriber.onComplete();
            }
        }

        @Override
        public void cancel()
        {
        }
    }

    public static class Base64CompressedStringProcessor implements Processor<String, ByteBuffer>, Subscription
    {
        private final Base64.Decoder base64 = Base64.getDecoder();
        private final GZIPContentDecoder gzip = new GZIPContentDecoder();
        private Subscriber<? super ByteBuffer> subscriber;
        private Subscription subscription;
        private long demand;
        private boolean dataAvailable;
        private boolean stalled;
        private String data;
        private int begin;

        @Override
        public void subscribe(Subscriber<? super ByteBuffer> subscriber)
        {
            this.subscriber = subscriber;
            subscriber.onSubscribe(this);
        }

        @Override
        public void onSubscribe(Subscription subscription)
        {
            this.subscription = subscription;
            // This is a processor, so it must not call request() now,
            // but only when its subscriber does, see below #request(long).
        }

        @Override
        public void onNext(String item)
        {
            data = item;
            begin = 0;
            process();
        }

        @Override
        public void onComplete()
        {
            subscriber.onComplete();
        }

        @Override
        public void onError(Throwable failure)
        {
            subscriber.onError(failure);
        }

        @Override
        public void request(long demand)
        {
            this.demand += demand;

            // Makes sure that onNext(String) is called
            // only where there is demand for ByteBuffers.
            // TODO: the logic in this block is kind of duplicated
            // TODO: in process() when the data is fully consumed.
            if (!dataAvailable)
            {
                dataAvailable = true;
                subscription.request(1);
            }

            // Normal stall handling.
            if (stalled)
            {
                stalled = false;
                process();
            }
        }

        @Override
        public void cancel()
        {
        }

        private void process()
        {
            // In Base64, for every 4 chars, I get 3 bytes.
            char[] block = new char[4];
            while (true)
            {
                data.getChars(begin, begin + block.length, block, 0);
                begin += block.length;
                CharBuffer chars = CharBuffer.wrap(block);
                ByteBuffer gzipped = base64.decode(StandardCharsets.US_ASCII.encode(chars));

                // For this test, assume the gzipped bytes are
                // always fully consumed by the GZIP decoder.
                ByteBuffer bytes = gzip.decode(gzipped);
                if (bytes.hasRemaining())
                {
                    --demand;
                    subscriber.onNext(bytes);
                }

                // Do we have more demand ?
                if (demand <= 0)
                {
                    stalled = true;
                    break;
                }

                // Do we have more data to process ?
                if (begin >= data.length())
                {
                    // Nope, request more data because we
                    // know we have demand for ByteBuffers.
                    // TODO: duplicated logic, see above.
                    subscription.request(1);
                    break;
                }
            }
        }
    }
}
