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

### Iterating Processor

### No acknowledgment






