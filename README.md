# Jetty Experimentation with Reactive API

This repository contains some experiments for using the [Reactive API]( http://www.reactive-streams.org/ "Reactive Streams") with the [Jetty server](http://eclipse.org/jetty).

## Overview
The basic experiments is to see how the concept of a reactive Flow stream can work with asynchronous IO in Jetty.  Asynchronous IO has two API styles within jetty: the internal Callback style; and the standard Servlet standard style.  While both styles are functional and have some good aspects, both can be difficult to use without race conditions and other asynchronous programming errors, thus it is attractive to consider the Flow API style which is meant to address some of these concerns.

Furthermore, the stream flow control mechanism of HTTP2 is very similar in concept to the back pressure mechanism of reactive streams, thus there could be signficiant benefit in linking these up with uniform semantics from application to connector.

Thus the base model these experiment considers is either:
* A web application using Reactive Streams internally that are implement over the standard Servet async IOAPI as ultimate sink/source
* A web application using the standard Servlet async IO API that is implemented internally to the server using Reactive Streams
* A web application using Reactive Streams both within the application and within the server so that the semantic goes all the way to the connector.

## FragmentingProcessor Branch
In the FragmentationProcessor Branch, we are looking at how the standard ServletOutputStream could be implemented using Reactive Streams.   Typically between the implementation of the ServletOutputStream and the HTTP connector there are several processing steps that may take place:
+ aggregation of small writes into a larger buffer that will be flushed efficiently
+ compression of written data
+ fragmentation of written data into HTTP2 frames that match the negotiated max frame size
+ queuing of data frames into streams that are flow controlled by HTTP2 and/or TCP/IP
+ SSL encryption of frames

Each of these processing steps could potentially be implemented as Flow Processors, except that ideally rather than queuing frames, the HTTP2 flow control mechanism could be directly wired to the Reactive Stream back pressure mechanism to stop frames being created/sent rather than queuing them.    In this branch, the FragmentingProcessor has been used as a rough approximation of an generic processing step.

The initial impression is that there is a good match between the styles, there are several problems that we have come up against that we cannot solve within the existing Reactive Stream API:

### Subscription request(int) units
The reactive API is templated over an item type and the back pressure API is based on the number of such items.  However, for IO purposes there is a missmatch because data is normally transfered in efficient aggregates (eg byte arrays or ByteBuffer instances) while back pressure would be best expressed as the total size of the aggregates rather than the total number of them.   Ie. there is a big difference between receiving 1 ByteBuffer of size 4GB and 4G of ByteBuffers of size 1B.

For the purposes of this branch, we have ignored this missmatch and are trying to see if we can make it work with back pressure expressed in terms of numbers of buffers rather than size of buffers.

### Asynchronous Processors
The [FragmentingProcessor](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/FragmentingProcessor.java) is both a Subscriber and a Publisher of ByteBuffers.  However, there is not a 1:1 mapping between buffers received and buffers sent, as a large buffer received in a call to onNext may produce multiple fragmented buffers.   This causes a problem for the implementation because it cannot use the back pressure mechanism to prevent a buffer being delivered until it can be handled.   Thus the implementation is faced with the dilemma of either blocking until sufficient requests have been received, or copying the received data, which would put an unacceptable memory burden on the implementation (specially as some ByteBuffers may be file mapped buffers that are too large to fit in memory).

The current implementation is [blocking on a semaphore](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/FragmentingProcessor.java#L52), but this undesirable for a scalable server.  Thus we are seeking a way to implement asynchronously but without copies, either by a design we have not though of or by changes to the API.  

To solve this maintaining consistency with understanding of the Reactive Stream paradigm is that we need a mechanism to prevent the original producer from sending something that cannot be handled immediately in the chain of processors.  This could be possibly be achieved by adding a size argument to the back pressure mechanism so that the signature becomes Subscription.request(long items, long size), which would essentially this would push fragmentation back to the origin producer.  However, I do not believe that all items can be fragmented, more so at source, thus I don't think this is a general solution.

### Mutable Items
Typical usage of an OutputStream API will reuse a buffer over and over for subsequent writes.  In the example in this branch, the Main class [re-uses a buffer](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/Main.java#L35) that it fills with a count of 1 to 10 on subsequent writes.     

With callback APIs and the Server API it is clear when a buffer maybe reused as the user can tell when the previous operation has completed (either by callback or by calling isReady()).  However, there is no such completion mechanism with Reactive Streams and thus it may not be safe to do such reuse.  The example implementation of [ServletOutputStreamProducer](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/ServletOutputStreamProducer.java#L122), simply checks if sufficient demand has been received by request(long) calls and calls onNext with the passed byte array wrapped as a buffer.  Consider if a request(10) call had been received, then this example would allow the [example writer](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/Main.java#L25) to iterate 10 times, each time reusing the buffer to perform a write.  If any Processors are in the chain that actually queue passed items, then this implementation will fail as the wrapped buffers of prior writes will be mutated by subsequent writes. 

It is unclear how Reactive Stream are meant to deal with Mutable Items other than by copying?


### Back pressure on close

This example has mapped a call to OutputStream.close() to a call to [Subscriber.onCompleted()](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/ServletOutputStreamProducer.java#L128).    The example processor simply [passes on the onComplete](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/FragmentingProcessor.java#L71) call.

However, there is nothing in the Reactive Streams model that allows back pressure to be applied to close as it assumes that onComplete does not need to send any items.  This is not true in general and some processors will need to produce down stream items on completion. For example, both HTTP2 Streams and SSL both have close handshakes that must be sent to gracefully close stream and/or connection.    If the stream is busy completing a previous write/onNext, then it may not be able to immediately process an onComplete call.  Processors will either have to block until they can publish extra items, or alternatively queue the completion so it can be delivered asynchronously.   While queuing the completion is low memory overhead, the lack of a completion notification mechanism will mean that the user will never know why the close has completed or if it has completed successfully (ie when it can no longer expect any onError callbacks).
