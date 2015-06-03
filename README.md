# Jetty Experimentation with Reactive API

This repository contains some experiments for using the [Reactive Stream API]( http://www.reactive-streams.org/ "Reactive Streams") with the [Jetty server](http://eclipse.org/jetty).

## Overview
The basic experiments is to see how the concept of a Reactive Stream (RS) can work with asynchronous IO in Jetty, both as a technique to implement a webapplication and as a technique to implement the server itself.   We consider the real asynchronous IO mechanisms already implemented in Jetty and applications to be very good use-cases with which to test the Reactive Stream API. Ie, can the Reactive Stream API be used to meet all the key concerns in the Jetty server and its asynchronous applications.

There are two styles of async IO API used within the jetty server: a Callback style used mostly by the server implementation; and the standard Servlet standard style used mostly by applications.  While both styles are functional and have some good aspects, both styles need care to be used without race conditions and other asynchronous programming errors.  Thus it is attractive to consider the Reactive Stream API style as it is meant to address such concerns.

Furthermore, the stream flow control mechanism of HTTP2 is very similar in concept to the back pressure mechanism of reactive streams, thus there could be significant benefit in linking these up with uniform semantics from application to connector.

Thus the base model these experiment considers is either:
* A web application using Reactive Streams internally that are implement over the standard Servet async IOAPI as ultimate sink/source (master branch)
* A web application using the standard Servlet async IO API that is implemented internally to the server using Reactive Streams (FragmentingProcessor Branch)

## FragmentingProcessor Branch
In the [FragmentationProcessor Branch](https://github.com/jetty-project/jetty-reactive/tree/FragmentingProcessor), we are looking at how the standard ServletOutputStream could be implemented using Reactive Streams.   

Typically between the implementation of the ServletOutputStream and the network socket there are several processing steps that take place:
+ aggregation of small writes into a larger buffer that will be flushed efficiently
+ compression of written data
+ fragmentation of written data into HTTP2 frames that match the negotiated max frame size
+ queuing of data frames into streams that are flow controlled by HTTP2 and/or TCP/IP
+ SSL encryption of frames

In the current Jetty implementation, each of these steps is typically implemented using our [IteratingCallback](https://github.com/eclipse/jetty.project/blob/master/jetty-util/src/main/java/org/eclipse/jetty/util/IteratingCallback.java) abstraction that allows a single asynchronous task to be broken down into multiple asynchronous sub tasks using iteration rather than recursion (which can be an issue in callback style APIs).   However, to use Reactive Streams, we would like to consider if such steps could be done using the Processor abstraction, so in this branch the FragmentingProcessor has been used as a rough approximation of a generic processing step.

### Iterating Processor
As with many async frameworks, there is a risk of deep recursion with ReactiveStream as Subscriber.onNext(T item) may call Subscription.request(n), which may in turn call Subscriber.onNext(T item).  Thus this branch contains the abstract [IteratingProcessor](https://github.com/jetty-project/jetty-reactive/blob/FragmentingProcessor/src/main/java/org/eclipse/jetty/reactive/IteratingProcessor.java) that is based on the Jetty [IteratingCallback](https://github.com/eclipse/jetty.project/blob/master/jetty-util/src/main/java/org/eclipse/jetty/util/IteratingCallback.java) to flatten such recursion into iteration.

### Two out of three ain't bad?
There are three desirable attributes of a IO Processor: 1) asynchronous so that threads are not consumed while waiting; 2) bounded memory, so that unlimited memory is not consumed in internal queues etc.; 3) Acknowledged, so that callers can know when processing has completed with an item and recycle it (eg return a buffer to a pool).   However it appears that working within the standard ReactiveStream API you can only ever have 2 our of these 3 attributes.    In this branch we have implemented asynchronous and bounded memory, but not acknowlegement.   Thus it is not possible for a caller to know: when a ByteBuffer passed to the FragmentatingProcessor can be recycled; when a byte[] passed to the StdErrSubscriber can be can be recycled; or when onCompleted processing is completed so that the demo may exit.   Thus in this example we do not recycle buffers (garbage!) and have gone outside of the standard API to determine when onCompleted() has propagated to the ultimate Subscriber.

### Eat What You Kill!
As an interesting observation, the Reactive Stream style has a lot in common with the [Eat What You Kill](https://webtide.com/eat-what-you-kill/) scheduling strategy implemented in Jetty.  EWYK achieves mechanical sympathy by implementing the ethos of a thread should not produce a task unless it is able to immediately consume it.  This avoids queues, dispatch delays and allows tasks to be consumed with hot tasks.  

RS style encourages similar mechanical sympathies by requiring publishers to not publish until there is demands that originates from the ultimate subscriber.  This can allow a single thread to call through a chain of onNext calls, keeping it's cache hot while avoiding queues and dispatch delays.





