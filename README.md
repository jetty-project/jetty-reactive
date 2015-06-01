# Jetty Experimentation with Reactive API

This repository contains some experiments for using the [Reactive API]( http://www.reactive-streams.org/ "Reactive Streams") with the [Jetty server](http://eclipse.org/jetty).

## Overview
The basic experiments is to see how the concept of a Reactive Stream can work with asynchronous IO in Jetty, both as a technique to implement a webapplication and as a technique to implement the server itself.   We consider the real asynchronous IO mechanisms already implemented in Jetty and applications to be very good use-cases with which to test the Reactive Stream API. Ie, can the Reactive Stream API be used to meet all the key concerns in the Jetty server and its asynchronous applications.

There are two styles of async IO API used within the jetty server: a Callback style used mostly by the server implementation; and the standard Servlet standard style used mostly by applications.  While both styles are functional and have some good aspects, both styles need care to be used without race conditions and other asynchronous programming errors.  Thus it is attractive to consider the Reactive Stream API style as it is meant to address such concerns.

Furthermore, the stream flow control mechanism of HTTP2 is very similar in concept to the back pressure mechanism of reactive streams, thus there could be significant benefit in linking these up with uniform semantics from application to connector.

Thus the base model these experiment considers is either:
* A web application using Reactive Streams internally that are implement over the standard Servet async IOAPI as ultimate sink/source (master branch)
* A web application using the standard Servlet async IO API that is implemented internally to the server using Reactive Streams (FragmentingProcessor Branch)

## FragmentingProcessor Branch
In the FragmentationProcessor Branch, we are looking at how the standard ServletOutputStream could be implemented using Reactive Streams.   

Typically between the implementation of the ServletOutputStream and the network socket there are several processing steps that take place:
+ aggregation of small writes into a larger buffer that will be flushed efficiently
+ compression of written data
+ fragmentation of written data into HTTP2 frames that match the negotiated max frame size
+ queuing of data frames into streams that are flow controlled by HTTP2 and/or TCP/IP
+ SSL encryption of frames

In the current Jetty implementation, each of these steps is typically implemented using our [IteratingCallback](https://github.com/eclipse/jetty.project/blob/master/jetty-util/src/main/java/org/eclipse/jetty/util/IteratingCallback.java) abstraction that allows a single asynchronous task to be broken down into multiple asynchronous sub tasks using iteration rather than recursion (which can be an issue in callback style APIs).   However, to use Reactive Streams, we would like to consider if such steps could be done using the Processor abstraction, so in this branch the FragmentingProcessor has been used as a rough approximation of a generic processing step.

While implementing this model as Reactive Streams has been moderately straight forward, there are several problems that we have come up against that we cannot solve within the existing Reactive Stream API:

### Subscription request(int) units
The reactive API is templated over an item type and the back pressure API is based on the number of such items.  However, for IO purposes there is a missmatch because data is normally transfered in efficient aggregates (eg byte arrays or ByteBuffer instances) while back pressure would be best expressed as the total size of the aggregates rather than the total number of them.   Ie. there is a big difference between receiving 1 ByteBuffer of size 4GB and 4G of ByteBuffers of size 1B.

For the purposes of this branch, we have ignored this miss match and are trying to see if we can make it work with back pressure expressed in terms of numbers of buffers rather than size of buffers.

### Asynchronous Processors
The [FragmentingProcessor](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/FragmentingProcessor.java) is both a Subscriber and a Publisher of ByteBuffers.  However, there is not a 1:1 mapping between buffers received and buffers sent, as a large buffer received in a call to onNext may produce multiple fragmented buffers.   This causes a problem for the implementation because it cannot use the back pressure mechanism to prevent a buffer being delivered until it can be handled.   Thus the implementation is faced with the dilemma of either blocking until sufficient requests have been received, or copying the received data, which would put an unacceptable memory burden on the implementation (specially as some ByteBuffers may be file mapped buffers that are too large to fit in memory).

The current implementation is [blocking on a semaphore](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/FragmentingProcessor.java#L52), but this undesirable for a scalable server.  Thus we are seeking a way to implement asynchronously but without copies, either by a design we have not though of or by changes to the API.  

To solve this maintaining consistency with understanding of the Reactive Stream paradigm is that we need a mechanism to prevent the original producer from sending something that cannot be handled immediately in the chain of processors.  This could be possibly be achieved by adding a size argument to the back pressure mechanism so that the signature becomes Subscription.request(long items, long size), which would essentially this would push fragmentation back to the origin producer.  However, I do not believe that all items can be fragmented, more so at source, thus I don't think this is a general solution.

### Mutable Items
Typical usage of an OutputStream API will reuse a buffer over and over for subsequent writes.  In the example in this branch, the Main class [re-uses a buffer](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/Main.java#L35) that it fills with a count of 1 to 10 on subsequent writes.     

With callback APIs and the Server API it is clear when a buffer maybe reused as the user can tell when the previous operation has completed (either by callback or by calling isReady()).  However, there is no such completion mechanism with Reactive Streams and thus it may not be safe to do such reuse.  The example implementation of [ServletOutputStreamProducer](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/ServletOutputStreamProducer.java#L122), simply checks if sufficient demand has been received by request(long) calls and calls onNext with the passed byte array wrapped as a buffer.  Consider if a request(10) call had been received, then this example would allow the [example writer](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/Main.java#L25) to iterate 10 times, each time reusing the buffer to perform a write.  If any Processors are in the chain that actually queue passed items, then this implementation will fail as the wrapped buffers of prior writes will be mutated by subsequent writes. 

It is unclear how Reactive Stream are meant to deal with mutable Items other than by copying?


### Back pressure on close
This example has mapped a call to OutputStream.close() to a call to [Subscriber.onCompleted()](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/ServletOutputStreamProducer.java#L128).    The example processor simply [passes on the onComplete](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/FragmentingProcessor.java#L71) call.

However, there is nothing in the Reactive Streams model that allows back pressure to be applied to close as it assumes that onComplete does not need to send any items.  This is not true in general and some processors will need to produce down stream items on completion. For example, Gzip compression, HTTP2 Streams and SSL all have close handshakes that must be sent to gracefully close stream and/or connection. There are also aggregation buffers that are only flushed on overflow or close, which make a close conversation large.    If the stream is busy completing a previous write/onNext, then it may not be able to immediately process an onComplete call to completion.  Processors will either have to block until they can publish extra items, or alternatively queue the completion so it can be delivered asynchronously.   While queuing the completion is low memory overhead, the lack of a completion notification mechanism will mean that the user will never know why the close has completed or if it has completed successfully (ie when it can no longer expect any onError callbacks).
