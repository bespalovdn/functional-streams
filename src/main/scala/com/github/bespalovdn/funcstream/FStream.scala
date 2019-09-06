package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.config.ReadTimeout
import com.github.bespalovdn.funcstream.impl.PublisherProxy
import com.github.bespalovdn.funcstream.mono.Producer.ProducerImpl
import com.github.bespalovdn.funcstream.mono.{Consumer, Producer, Publisher}
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.Try

trait FStream[+R, -W]{
    def read[R0 >: R]()(implicit timeout: ReadTimeout): Future[R0]
    def readWithFilter[R1](filter: R => Option[R1])(implicit timeout: ReadTimeout): Future[R1]
    def readOrStash[R1](reader: R => Option[Future[R1]])(implicit timeout: ReadTimeout): Future[R1]
    def write[W1 <: W](elem: W1): Future[Unit]
    def interactWith[R0 >: R, W1 <: W, C](consumer: FConsumer[R0, W1, C]): Future[C]
    def <=> [R0 >: R, W1 <: W, C](consumer: FConsumer[R0, W1, C]): Future[C] = interactWith(consumer)
    def filter(fn: R => Boolean, name: String = null): FStream[R, W]
    def filterNot(fn: R => Boolean, name: String = null): FStream[R, W]
    def transform [C, D](down: R => C, up: D => W, name: String = null): FStream[C, D]
    def transformWithFilter[C, D](down: R => Option[C], up: D => W, name: String = null): FStream[C, D]
    def fork(name: String = null): FStream[R, W]

    def close(): Future[Unit]
    def closed: Future[Unit]
}

object FStream
{
    private lazy val logger = LoggerFactory.getLogger(getClass)

    def apply[R, W](connection: Connection[R, W], name: String = null): FStream[R, W] = new FStreamImpl[R, W](Option(name), connection)

    private class FStreamImpl[R, W](name: Option[String], connection: Connection[R, W])
        extends FStream[R, W]
    {
        private def log(msg: String): Unit = {
            if(name.nonEmpty)
                logger.warn(name + ": " + msg)
        }

        private val upStream: Producer[_ <: R] = Producer(connection, name.orNull)

        override def read[R0 >: R]()(implicit timeout: ReadTimeout): Future[R0] = upStream.get()

        override def readWithFilter[R1](filter: R => Option[R1])(implicit timeout: ReadTimeout): Future[R1] = upStream.getWithFilter(filter)

        override def readOrStash[R1](reader: R => Option[Future[R1]])(implicit timeout: ReadTimeout): Future[R1] = upStream.getOrStash(reader)

        override def write[W1 <: W](elem: W1): Future[Unit] = connection.write(elem)

        override def interactWith [R0 >: R, W1 <: W, C](c: FConsumer[R0, W1, C]): Future[C] = {
            val consumer = Consumer[R, C] { _ => c.consume(this) }
            upStream ==> consumer
        }

        override def transform [C, D](down: R => C, up: D => W, name: String): FStream[C, D] = {
            val transformed: Producer[C] = upStream.transform(down, name)
            producer2stream(name, transformed, up)
        }

        override def transformWithFilter[C, D](down: R => Option[C], up: D => W, name: String): FStream[C, D] = {
            val transformed: Producer[C] = upStream.transformWithFilter(down, name)
            producer2stream(name, transformed, up)
        }

        override def filter(fn: R => Boolean, name: String): FStream[R, W] = {
            val filtered = upStream.filter(fn, name)
            producer2stream(name, filtered, identity)
        }

        override def filterNot(fn: R => Boolean, name: String): FStream[R, W] = filter(a => !fn(a), name)

        override def fork(name: String): FStream[R, W] = producer2stream(name, upStream.fork(name), identity)

        override def close(): Future[Unit] = connection.close()

        override def closed: Future[Unit] = connection.closed

        private def producer2stream[C, D](name: String, producer: Producer[C], transformUp: D => W): FStream[C, D] = {
            def getPublisher(producer: Producer[C]): Publisher[C] with Resource = producer.asInstanceOf[ProducerImpl[C]].publisher
            def toStream(producer: Producer[C]): FStream[C, D] = new FStreamImpl[C, D](Option(name), new ProxyEndPoint(getPublisher(producer), transformUp))
            toStream(producer)
        }

        private class ProxyEndPoint[C, D](val upstream: Publisher[C] with Resource, transformUp: D => W) extends Connection[C, D] with PublisherProxy[C, C] {
            override def write(elem: D): Future[Unit] = try { connection.write(transformUp(elem)) } catch { case t: Throwable => Future.failed(t) }
            override def push(elem: Try[C]): Unit = {
                log("ProxyEndPoint: push elem: " + elem)
                forEachSubscriber(_.push(elem))
            }
        }

    }
}
