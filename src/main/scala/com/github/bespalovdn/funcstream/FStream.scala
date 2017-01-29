package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.impl.PublisherProxy
import com.github.bespalovdn.funcstream.mono.Producer.ProducerImpl
import com.github.bespalovdn.funcstream.mono.{Consumer, Producer, Publisher}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait FStream[A, B]{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]
    def consume [C](consumer: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C]
    def transform [C, D](down: A => C, up: D => B): FStream[C, D]
    def filter(fn: A => Boolean): FStream[A, B]
    def filterNot(fn: A => Boolean): FStream[A, B]
    def fork(): FStream[A, B]
    def addListener(listener: A => Unit): FStream[A, B]

    def <=> [C](consumer: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C] = consume(consumer)
}

object FStream
{
    def apply[A, B](connection: Connection[A, B]): FStream[A, B] = new FStreamImpl[A, B](connection)

    private class FStreamImpl[A, B](connection: Connection[A, B])
        extends FStream[A, B]
    {
        private val reader: Producer[A] = Producer(connection)

        override def read(timeout: Duration): Future[A] = reader.get(timeout)

        override def write(elem: B): Future[Unit] = {
            connection.write(elem)
            success()
        }

        override def consume[C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C] = {
            val consumer = Consumer[A, C] { _ => c.apply(this) }
            reader consume consumer
        }

        override def transform [C, D](down: A => C, up: D => B): FStream[C, D] = {
            val transformed: Producer[C] = reader.transform(down)
            producer2stream(transformed, up)
        }

        override def filter(fn: (A) => Boolean): FStream[A, B] = {
            val filtered = reader.filter(fn)
            producer2stream(filtered, identity)
        }

        override def filterNot(fn: A => Boolean): FStream[A, B] = filter(a => !fn(a))

        override def fork(): FStream[A, B] = producer2stream(reader.fork(), identity)

        override def addListener(listener: (A) => Unit): FStream[A, B] = {
            reader.addListener(listener)
            this
        }

        private def producer2stream[C, D](producer: Producer[C], transformUp: D => B): FStream[C, D] = {
            def getPublisher(producer: Producer[C]): Publisher[C] = producer.asInstanceOf[ProducerImpl[C]].publisher
            def toStream(producer: Producer[C]): FStream[C, D] = new FStreamImpl[C, D](new ProxyEndPoint(getPublisher(producer), transformUp))
            toStream(producer)
        }

        private class ProxyEndPoint[C, D](val upstream: Publisher[C], transformUp: D => B) extends Connection[C, D] with PublisherProxy[C, C] {
            override def write(elem: D): Unit = connection.write(transformUp(elem))
            override def push(elem: Try[C]): Unit = forEachSubscriber(_.push(elem))
        }

    }
}
