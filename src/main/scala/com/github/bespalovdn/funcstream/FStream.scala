package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.impl.PublisherProxy
import com.github.bespalovdn.funcstream.mono.Producer.ProducerImpl
import com.github.bespalovdn.funcstream.mono.{Consumer, Producer, Publisher}

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

trait FStream[+R, -W]{
    def read[R0 >: R](timeout: Duration = null): Future[R0]
    def write[W1 <: W](elem: W1): Future[Unit]
    def interactWith[R0 >: R, W1 <: W, C](consumer: FConsumer[R0, W1, C]): Future[C]
    def <=> [R0 >: R, W1 <: W, C](consumer: FConsumer[R0, W1, C]): Future[C] = interactWith(consumer)
    def filter(fn: R => Boolean): FStream[R, W]
    def filterNot(fn: R => Boolean): FStream[R, W]
    def transform [C, D](down: R => C, up: D => W): FStream[C, D]
    def transformWithFilter[C, D](down: R => Option[C], up: D => W): FStream[C, D]
    def fork(): FStream[R, W]
    def addListener[R0 >: R](listener: Try[R0] => Unit): FStream[R, W]

    def close(): Future[Unit]
    def closed: Future[Unit]
}

object FStream
{
    def apply[R, W](connection: Connection[R, W]): FStream[R, W] = new FStreamImpl[R, W](connection)

    private class FStreamImpl[R, W](connection: Connection[R, W])
        extends FStream[R, W]
    {
        private val upStream: Producer[_ <: R] = Producer(connection)

        override def read[R0 >: R](timeout: Duration): Future[R0] = upStream.get(timeout)

        override def write[W1 <: W](elem: W1): Future[Unit] = connection.write(elem)

        override def interactWith [R0 >: R, W1 <: W, C](c: FConsumer[R0, W1, C]): Future[C] = {
            val consumer = Consumer[R, C] { _ => c.consume(this) }
            upStream ==> consumer
        }

        override def transform [C, D](down: R => C, up: D => W): FStream[C, D] = {
            val transformed: Producer[C] = upStream.transform(down)
            producer2stream(transformed, up)
        }

        override def transformWithFilter[C, D](down: (R) => Option[C], up: (D) => W): FStream[C, D] = {
            val transformed: Producer[C] = upStream.transformWithFilter(down)
            producer2stream(transformed, up)
        }

        override def filter(fn: R => Boolean): FStream[R, W] = {
            val filtered = upStream.filter(fn)
            producer2stream(filtered, identity)
        }

        override def filterNot(fn: R => Boolean): FStream[R, W] = filter(a => !fn(a))

        override def fork(): FStream[R, W] = producer2stream(upStream.fork(), identity)

        override def close(): Future[Unit] = connection.close()

        override def closed: Future[Unit] = connection.closed

        override def addListener[R0 >: R](listener: Try[R0] => Unit): FStream[R, W] = {
            upStream.addListener(listener)
            this
        }

        private def producer2stream[C, D](producer: Producer[C], transformUp: D => W): FStream[C, D] = {
            def getPublisher(producer: Producer[C]): Publisher[C] with Resource = producer.asInstanceOf[ProducerImpl[C]].publisher
            def toStream(producer: Producer[C]): FStream[C, D] = new FStreamImpl[C, D](new ProxyEndPoint(getPublisher(producer), transformUp))
            toStream(producer)
        }

        private class ProxyEndPoint[C, D](val upstream: Publisher[C] with Resource, transformUp: D => W) extends Connection[C, D] with PublisherProxy[C, C] {
            override def write(elem: D): Future[Unit] = try { connection.write(transformUp(elem)) } catch { case t: Throwable => Future.failed(t) }
            override def push(elem: Try[C]): Unit = forEachSubscriber(_.push(elem))
        }

    }
}
