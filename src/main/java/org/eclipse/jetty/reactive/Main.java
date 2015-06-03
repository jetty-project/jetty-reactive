package org.eclipse.jetty.reactive;

import java.io.IOException;
import java.util.Arrays;

import javax.servlet.WriteListener;

import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

public class Main
{
    public static void main(String... arg) throws Exception
    {
        // scheduler for simulating latency
        final Scheduler scheduler = new ScheduledExecutorScheduler();
        scheduler.start();

        final StdErrSubscriber sink = new StdErrSubscriber(scheduler,1);
        final FragmentingProcessor chain = new FragmentingProcessor(128);
        final ServletOutputStreamProducer out = new ServletOutputStreamProducer();
        chain.subscribe(sink);
        out.subscribe(chain);

        WriteListener writer = new WriteListener()
        {
            byte[] buf = new byte[1000];
            int count=0;

            @Override
            public void onWritePossible() throws IOException
            {
                while(out.isReady())
                {
                    Arrays.fill(buf,(byte)('0'+count));
                    out.write(buf,0,buf.length);

                    if (++count==10)
                    {
                        try
                        {
                            out.close();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                        break;
                    }
                }
            }

            @Override
            public void onError(Throwable t)
            {
                t.printStackTrace();
            }
        };

        out.setWriteListener(writer);
        
        sink.waitUntilComplete();
        scheduler.stop();

    }
}
