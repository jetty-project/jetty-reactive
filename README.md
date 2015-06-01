# Jetty Experimentation with Reactive API

This repository contains some experiments for using the [Reactive API]( http://www.reactive-streams.org/ "Reactive Streams") with the [Jetty server](http://eclipse.org/jetty).

## Overview
The basic experiments is to see how the concept of a reactive Flow stream can work with asynchronous IO in Jetty.  Asynchronous IO has two API styles within jetty: the internal Callback style; and the standard Servlet standard style.  While both styles are functional and have some good aspects, both can be difficult to use without race conditions and other asynchronous programming errors, thus it is attractive to consider the Flow API style which is meant to address some of these concerns.

Furthermore, the stream flow control mechanism of HTTP2 is very similar in concept to the back pressure mechanism of reactive streams, thus there could be signficiant benefit in linking these up with uniform semantics from application to connector.

Thus the base model these experiment considers is either:
* A web application using Reactive Streams internally that are implement over the standard Servet async IOAPI as ultimate sink/source
* A web application using the standard Servlet async IO API that is implemented internally to the server using Reactive Streams
* A web application using Reactive Streams both within the application and within the server so that the semantic goes all the way to the connector.

## FragmentationProcessor Branch
In the FragmentationProcessor Branch, we are looking at how the standard ServletOutputStream could be implemented using Reactive Streams.   Typically between the implementation of the ServletOutputStream and the HTTP connector there are several processing steps that may take place:
+ aggregation of small writes into a larger buffer that will be flushed efficiently
+ compression of written data
+ fragmentation of written data into HTTP2 frames that match the negotiated max frame size
+ queuing of data frames into streams that are flow controlled by HTTP2 and/or TCP/IP
+ SSL encryption of frames

Each of these processing steps could potentially be implemented as Flow Processors, except that ideally rather than queuing frames, the HTTP2 flow control mechanism could be directly wired to the Reactive Stream back pressure mechanism to stop frames being created/sent rather than queuing them.

The initial impression is that there is a good match between the styles, there are several problems that we have come up against that we cannot solve within the existing Reactive Stream API:

### Subscription request(int) units

### Asynchronous Processors

### Item ownership

### Back pressure on close