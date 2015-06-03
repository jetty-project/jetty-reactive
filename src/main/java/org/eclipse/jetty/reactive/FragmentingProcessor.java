package org.eclipse.jetty.reactive;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;



/** 
 * Processor that will split subscribed buffers to a maximum size.
 */
public class FragmentingProcessor extends IteratingProcessor<ByteBuffer, byte[]>
{
    private final int maxBufferSize;
    
    public FragmentingProcessor(int maxBufferSize)
    {
        this.maxBufferSize=maxBufferSize;
    }

    @Override
    protected byte[] process(ByteBuffer item, boolean complete)
    {
        int length = BufferUtil.length(item);
        int chunk = Math.min(length,maxBufferSize);
        if (chunk==0)
            return null;
        byte[] buffer = new byte[chunk];
        item.get(buffer,0,chunk);
        return buffer;
    }

}
