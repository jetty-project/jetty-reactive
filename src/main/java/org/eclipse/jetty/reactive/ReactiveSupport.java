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
import javax.servlet.http.HttpServletRequest;

import org.reactivestreams.Publisher;

public class ReactiveSupport
{
    private static final String PUBLISHER_ATTRIBUTE = "org.eclipse.jetty.reactive.publisher";

    private ReactiveSupport()
    {
    }

    public static Publisher<ByteBuffer> getPublisher(AsyncContext context) throws IOException
    {
        HttpServletRequest request = (HttpServletRequest)context.getRequest();
        RequestPublisher result = (RequestPublisher)request.getAttribute(PUBLISHER_ATTRIBUTE);
        if (result == null)
        {
            result = new RequestPublisher(context, 8192);
            request.setAttribute(PUBLISHER_ATTRIBUTE, result);
            request.getInputStream().setReadListener(result);
        }
        return result;
    }
}
