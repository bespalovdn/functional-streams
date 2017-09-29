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
    def <=> [R0 >: R, W1 <: W, C](consumer: FConsumer[R0, W1, C]): Future[C]
    def transform [C, D](down: R => C, up: D => W, name: String = null): FStream[C, D]
    def filter(fn: R => Boolean, name: String = null): FStream[R, W]
    def filterNot(fn: R => Boolean, name: String = null): FStream[R, W]
    def fork(name: String = null): FStream[R, W]
    def addListener[R0 >: R](listener: Try[R0] => Unit): FStream[R, W]
    def addSuccessListener(listener: R => Unit): FStream[R, W]
    def preSubscribe(): Unit
    def setDebugEnabled(enabled: Boolean)
}

object FStream
{
    def apply[R, W](connection: Connection[R, W], name: String = null): FStream[R, W] =
        new FStreamImpl[R, W](connection, false, name)

    private class FStreamImpl[R, W](connection: Connection[R, W], val _debugEnabled: Boolean, val name: String)
        extends FStream[R, W]
    {
        private val upStream: Producer[_ <: R] = Producer(connection, _debugEnabled, name)

        override def setDebugEnabled(enabled: Boolean): Unit = { upStream.setDebugEnabled(enabled) }

        override def read[R0 >: R](timeout: Duration): Future[R0] = upStream.get(timeout)

        override def write[W1 <: W](elem: W1): Future[Unit] = connection.write(elem)

        override def <=> [R0 >: R, W1 <: W, C](c: FConsumer[R0, W1, C]): Future[C] = {
            val consumer = Consumer[R, C] { _ => c.consume(this) }
            upStream ==> consumer
        }

        override def transform [C, D](down: R => C, up: D => W, name: String): FStream[C, D] = {
            val transformed: Producer[C] = upStream.transform(down, name)
            producer2stream(transformed, up)
        }

        override def filter(fn: R => Boolean, name: String): FStream[R, W] = {
            val filtered = upStream.filter(fn, name)
            producer2stream(filtered, identity)
        }

        override def filterNot(fn: R => Boolean, name: String): FStream[R, W] = filter(a => !fn(a), name)

        override def fork(name: String): FStream[R, W] = producer2stream(upStream.fork(name), identity)

        override def addListener[R0 >: R](listener: Try[R0] => Unit): FStream[R, W] = {
            upStream.addListener(listener)
            this
        }

        override def addSuccessListener(listener: R => Unit): FStream[R, W] = {
            upStream.addSuccessListener(listener)
            this
        }

        override def preSubscribe(): Unit = upStream.preSubscribe()

        private def producer2stream[C, D](producer: Producer[C], transformUp: D => W): FStream[C, D] = {
            val pImpl = producer.asInstanceOf[ProducerImpl[C]]
            def getPublisher(producer: Producer[C]): Publisher[C] = pImpl.publisher
            def toStream(producer: Producer[C]): FStream[C, D] =
                new FStreamImpl[C, D](new ProxyEndPoint(getPublisher(producer), transformUp), pImpl.isDebugEnabled, pImpl.name)
            toStream(producer)
        }

        private class ProxyEndPoint[C, D](val upstream: Publisher[C], transformUp: D => W)
            extends Connection[C, D] with PublisherProxy[C, C]
        {
            override def write(elem: D): Future[Unit] = connection.write(transformUp(elem))
            override def push(elem: Try[C]): Unit = forEachSubscriber(_.push(elem))
        }

    }
}
