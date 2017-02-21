package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.impl.PublisherProxy
import com.github.bespalovdn.funcstream.mono.Producer.ProducerImpl
import com.github.bespalovdn.funcstream.mono.{Consumer, Producer, Publisher}

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

trait FStream[A, B]{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]
    def consume [C](consumer: FConsumer[A, B, C]): Future[C]
    def <=> [C](consumer: FConsumer[A, B, C]): Future[C] = consume(consumer)
    def transform [C, D](down: A => C, up: D => B): FStream[C, D]
    def filter(fn: A => Boolean): FStream[A, B]
    def filterNot(fn: A => Boolean): FStream[A, B]
    def fork(): FStream[A, B]
    def addListener(listener: A => Unit): FStream[A, B]
    def subscribe(keepSubscribed: Boolean): Unit
    def unsubscribe(): Unit
}

object FStream
{
    def apply[A, B](connection: Connection[A, B]): FStream[A, B] = new FStreamImpl[A, B](connection)

    private class FStreamImpl[A, B](connection: Connection[A, B])
        extends FStream[A, B]
    {
        private val upStream: Producer[A] = Producer(connection)

        override def read(timeout: Duration): Future[A] = upStream.get(timeout)

        override def write(elem: B): Future[Unit] = connection.write(elem)

        override def consume[C](c: FConsumer[A, B, C]): Future[C] = {
            val consumer = Consumer[A, C] { _ => c.apply(this) }
            upStream ==> consumer
        }

        override def transform [C, D](down: A => C, up: D => B): FStream[C, D] = {
            val transformed: Producer[C] = upStream.transform(down)
            producer2stream(transformed, up)
        }

        override def filter(fn: (A) => Boolean): FStream[A, B] = {
            val filtered = upStream.filter(fn)
            producer2stream(filtered, identity)
        }

        override def filterNot(fn: A => Boolean): FStream[A, B] = filter(a => !fn(a))

        override def fork(): FStream[A, B] = producer2stream(upStream.fork(), identity)

        override def addListener(listener: (A) => Unit): FStream[A, B] = {
            upStream.addListener(listener)
            this
        }

        override def subscribe(keepSubscribed: Boolean): Unit = upStream.subscribe(keepSubscribed)
        override def unsubscribe(): Unit = upStream.unsubscribe()

        private def producer2stream[C, D](producer: Producer[C], transformUp: D => B): FStream[C, D] = {
            def getPublisher(producer: Producer[C]): Publisher[C] = producer.asInstanceOf[ProducerImpl[C]].publisher
            def toStream(producer: Producer[C]): FStream[C, D] = new FStreamImpl[C, D](new ProxyEndPoint(getPublisher(producer), transformUp))
            toStream(producer)
        }

        private class ProxyEndPoint[C, D](val upstream: Publisher[C], transformUp: D => B) extends Connection[C, D] with PublisherProxy[C, C] {
            override def write(elem: D): Future[Unit] = connection.write(transformUp(elem))
            override def push(elem: Try[C]): Unit = forEachSubscriber(_.push(elem))
        }

    }
}
