package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.impl.PublisherProxy
import com.github.bespalovdn.funcstream.mono.Producer.ProducerImpl
import com.github.bespalovdn.funcstream.mono.{Consumer, Producer, Publisher}

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

trait FStream[R, W]{
    def read(timeout: Duration = null): Future[R]
    def write(elem: W): Future[Unit]
    def consume [C](consumer: FConsumer[R, W, C]): Future[C]
    def <=> [C](consumer: FConsumer[R, W, C]): Future[C] = consume(consumer)
    def transform [C, D](down: R => C, up: D => W): FStream[C, D]
    def filter(fn: R => Boolean): FStream[R, W]
    def filterNot(fn: R => Boolean): FStream[R, W]
    def fork(): FStream[R, W]
    def addListener(listener: Try[R] => Unit): FStream[R, W]
    def preSubscribe(): Unit
}

object FStream
{
    def apply[R, W](connection: Connection[R, W]): FStream[R, W] = new FStreamImpl[R, W](connection)

    private class FStreamImpl[R, W](connection: Connection[R, W])
        extends FStream[R, W]
    {
        private val upStream: Producer[R] = Producer(connection)

        override def read(timeout: Duration): Future[R] = upStream.get(timeout)

        override def write(elem: W): Future[Unit] = connection.write(elem)

        override def consume[C](c: FConsumer[R, W, C]): Future[C] = {
            val consumer = Consumer[R, C] { _ => c.apply(this) }
            upStream ==> consumer
        }

        override def transform [C, D](down: R => C, up: D => W): FStream[C, D] = {
            val transformed: Producer[C] = upStream.transform(down)
            producer2stream(transformed, up)
        }

        override def filter(fn: (R) => Boolean): FStream[R, W] = {
            val filtered = upStream.filter(fn)
            producer2stream(filtered, identity)
        }

        override def filterNot(fn: R => Boolean): FStream[R, W] = filter(a => !fn(a))

        override def fork(): FStream[R, W] = producer2stream(upStream.fork(), identity)

        override def addListener(listener: Try[R] => Unit): FStream[R, W] = {
            upStream.addListener(listener)
            this
        }

        override def preSubscribe(): Unit = upStream.preSubscribe()

        private def producer2stream[C, D](producer: Producer[C], transformUp: D => W): FStream[C, D] = {
            def getPublisher(producer: Producer[C]): Publisher[C] = producer.asInstanceOf[ProducerImpl[C]].publisher
            def toStream(producer: Producer[C]): FStream[C, D] = new FStreamImpl[C, D](new ProxyEndPoint(getPublisher(producer), transformUp))
            toStream(producer)
        }

        private class ProxyEndPoint[C, D](val upstream: Publisher[C], transformUp: D => W) extends Connection[C, D] with PublisherProxy[C, C] {
            override def write(elem: D): Future[Unit] = connection.write(transformUp(elem))
            override def push(elem: Try[C]): Unit = forEachSubscriber(_.push(elem))
        }

    }
}
