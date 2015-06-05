package org.eclipse.jetty.reactive;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Utf8StringBuilder;

/*
 * A processor that converts ByteBuffers to a stream of name value pairs
 */
public class FormFieldProcessor extends IteratingProcessor<ByteBuffer,Fields.Field>
{
    final Utf8StringBuilder builder = new Utf8StringBuilder();
    String name;
    
    @Override
    protected Fields.Field process(ByteBuffer buffer)
    {
        String value=null;
        while(BufferUtil.hasContent(buffer))
        {
            byte b = buffer.get();
            if (name==null)
            {
                if (b=='=')
                {
                    name=builder.toString();
                    builder.reset();
                }
                else
                    builder.append(b);
            }
            else
            {
                if (b=='&')
                {
                    value=builder.toString();
                    builder.reset();
                    break;
                }
                else
                    builder.append(b);
            }
        }
        
        if (name!=null && value!=null)
        {
            Fields.Field field = new Fields.Field(name,value);
            name=null;
            return field;
        }
        return null;
    }

    
    
    @Override
    protected boolean isConsumed(ByteBuffer item)
    {
        return BufferUtil.isEmpty(item);
    }



    @Override
    protected Fields.Field complete()
    {
        if (name==null)
            return null;
        
        String value=builder.toString();
        builder.reset();
        
        Fields.Field field = new Fields.Field(name,value);
        name=null;
        return field;
    }
}
