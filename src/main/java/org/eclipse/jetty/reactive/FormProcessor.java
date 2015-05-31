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
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

import org.eclipse.jetty.util.Fields;
import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;


public class FormProcessor implements Processor<ByteBuffer, Fields>
{
    private final SimpleFormParser _parser = new SimpleFormParser("UTF-8");
    private Subscription _subscription;
    private Subscriber<? super Fields> _subscriber;

    @Override
    public void onSubscribe(Subscription subscription)
    {
        _subscription = subscription;
    }

    @Override
    public void onNext(ByteBuffer item)
    {
        _parser.parse(item);
        _subscription.request(1);
    }

    @Override
    public void onComplete()
    {
        _parser.close();
        _subscriber.onNext(_parser._fields);
        _subscriber.onComplete();
    }

    @Override
    public void onError(Throwable failure)
    {
        _subscriber.onError(failure);
    }

    @Override
    public void subscribe(Subscriber<? super Fields> subscriber)
    {
        if (_subscriber != null)
            throw new IllegalStateException();
        _subscriber = Objects.requireNonNull(subscriber);
        _subscriber.onSubscribe(new FormSubscription());
    }

    private class FormSubscription implements Subscription
    {
        @Override
        public void request(long n)
        {
            _subscription.request(n);
        }

        @Override
        public void cancel()
        {
            _subscription.cancel();
        }
    }

    private static class SimpleFormParser implements Closeable
    {
        private final Fields _fields = new Fields(true);
        private final ByteArrayOutputStream _store = new ByteArrayOutputStream();
        private final String _encoding;
        private State _state = State.NAME;
        private String _name;

        private SimpleFormParser(String encoding)
        {
            _encoding = encoding;
        }

        private void parse(ByteBuffer buffer)
        {
            while (buffer.hasRemaining())
            {
                byte current = buffer.get();
                switch (_state)
                {
                    case NAME:
                    {
                        if (current == '=')
                        {
                            _name = asString(_store.toByteArray(), _encoding);
                            _store.reset();
                            _state = State.VALUE;
                        }
                        else
                        {
                            _store.write(current);
                        }
                        break;
                    }
                    case VALUE:
                    {
                        if (current == '&')
                        {
                            String value = asString(_store.toByteArray(), _encoding);
                            _store.reset();
                            _fields.add(_name, value);
                            _state = State.NAME;
                        }
                        else
                        {
                            _store.write(current);
                        }
                        break;
                    }
                }
            }
        }

        private String asString(byte[] bytes, String encoding)
        {
            return new String(bytes, Charset.forName(encoding));
        }

        public void close()
        {
            String value = asString(_store.toByteArray(), _encoding);
            _store.reset();
            _fields.add(_name, value);
        }

        private enum State
        {
            NAME, VALUE
        }
    }
}
