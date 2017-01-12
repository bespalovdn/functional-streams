package com.github.bespalovdn.funcstream

import java.util.concurrent.ConcurrentLinkedQueue

import com.github.bespalovdn.funcstream.ext.{ClosableStream, ClosableStreamImpl}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait FStream[A, B]
{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]

    //TODO: think about forking for <=> FPipe
    // example:
//    private val stream: FStream[VC2SFUResponseMsg, VC2SFURequestMsg] = {
//        val stream = new WebSocketClientStream(endpoint)
//        // fork Ping/Pong handler:
//        stream <=> SfuClientProtocol.listenRead[WebSocketFrame, WebSocketFrame](m => logger.info("PingPong MSG: " + m)) <=>
//            SfuClientProtocol.filter[WebSocketFrame, WebSocketFrame](SfuClientProtocol.isPingPongFrame) <=>
//            SfuClientProtocol.pingPong
//        // return stream after WebSocketFrame <=> VC2SFU converter:
//        stream <=> SfuClientProtocol.listenRead[WebSocketFrame, WebSocketFrame](m => logger.info("REST MSG: " + m)) <=>
//            SfuClientProtocol.filterNot[WebSocketFrame, WebSocketFrame](SfuClientProtocol.isPingPongFrame) <=>
//            SfuClientProtocol.convertFrame
//    }
    def <=> [C, D](p: FPipe[A, B, C, D]): FStream[C, D] = p.apply(this)
    def <=> [C, D, X](c: FConsumer[A, B, C, D, X]): Future[X] = {
        val downStream = fork()
        val f = c.apply(downStream).map(_._2)
        f.onComplete(_ => downStream.close())
        f
    }

    private val readQueues = ListBuffer.empty[ConcurrentLinkedQueue[Future[A]]]
    private def upStream = this
    private def fork(): ClosableStream[A, B] = new DownStream

    private class DownStream extends ClosableStreamImpl[A, B]{
        import scala.concurrent.ExecutionContext.Implicits.global

        val readQueue = new ConcurrentLinkedQueue[Future[A]]()

        readQueues.synchronized{ readQueues += readQueue }
        closed.onComplete{_ => readQueues.synchronized{ readQueues -= readQueue }}

        override def write(elem: B): Future[Unit] = checkClosed{ upStream.synchronized{ upStream.write(elem) } }
        override def read(timeout: Duration): Future[A] = checkClosed{
            readQueue.poll() match {
                case null =>
                    val f = upStream.synchronized(upStream.read(timeout))
                    readQueues.foreach(_ offer f)
                    readQueue.poll()
                case elem =>
                    elem
            }
        }
    }
}
