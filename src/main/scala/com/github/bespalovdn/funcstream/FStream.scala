package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.impl.PublisherProxy
import com.github.bespalovdn.funcstream.mono.Producer.ProducerImpl
import com.github.bespalovdn.funcstream.mono.{Consumer, Producer, Publisher}

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

trait FStream[-R, +W]{
    def read[R1 <: R](timeout: Duration = null): Future[R1]
    def write[W1 >: W](elem: W1): Future[Unit]
    def consume [R1 <: R, W1 >: W, C](consumer: FConsumer[R1, W1, C]): Future[C]
    def <=> [R1 <: R, W1 >: W, C](consumer: FConsumer[R1, W1, C]): Future[C] = consume(consumer)
    def transform [R1 <: R, W1 >: W, C, D](down: R1 => C, up: D => W1): FStream[C, D]
    def filter[R1 <: R](fn: R1 => Boolean): FStream[R, W]
    def filterNot[R1 <: R](fn: R1 => Boolean): FStream[R, W]
    def fork(): FStream[R, W]
    def addListener[R1 <: R](listener: Try[R1] => Unit): FStream[R, W]
    def addSuccessListener[R1 <: R](listener: R1 => Unit): FStream[R, W]
    def preSubscribe(): Unit
}

object FStream
{
    def apply[R, W](connection: Connection[R, W]): FStream[R, W] = new FStreamImpl[R, W](connection)

    private class FStreamImpl[R, W](connection: Connection[R, W])
        extends FStream[R, W]
    {
        private val upStream: Producer[_ <: R] = Producer(connection)

        override def read[R1 >: R](timeout: Duration): Future[R1] = upStream.get(timeout)

        override def write[W1 <: W](elem: W1): Future[Unit] = connection.write(elem)

        override def consume[R1 <: R, W1 >: W, C](c: FConsumer[R1, W1, C]): Future[C] = {
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

        override def addSuccessListener(listener: R => Unit): FStream[R, W] = {
            upStream.addSuccessListener(listener)
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
