package com.github.bespalovdn.funcstream.v2

import com.github.bespalovdn.funcstream.ext.FutureExtensions._
import com.github.bespalovdn.funcstream.v2.Producer.ProducerImpl

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

trait EndPoint[A, B] extends Publisher[A]{
    def write(elem: B): Unit
}

trait FStream[A, B]{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]
    def <=> [C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C]
    def fork(consumer: FStream[A, B] => Unit): FStream[A, B]
}

object FStream
{
    def apply[A, B](endPoint: EndPoint[A, B]): FStream[A, B] = new FStreamImpl[A, B](endPoint)

    private class FStreamImpl[A, B](endPoint: EndPoint[A, B])
        extends FStream[A, B]
    {
        private val reader: Producer[A] = Producer(endPoint)

        override def read(timeout: Duration): Future[A] = reader.get(timeout)

        override def write(elem: B): Future[Unit] = {
            endPoint.write(elem)
            success()
        }

        override def <=>[C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C] = {
            val consumer = Consumer[A, C] { _ => c.apply(this) }
            consumer.apply(reader)
        }

        override def fork(consumer: FStream[A, B] => Unit): FStream[A, B] = {
            def getPublisher(producer: Producer[A]): Publisher[A] = producer.asInstanceOf[ProducerImpl[A]].publisher
            def toStream(producer: Producer[A]): FStream[A, B] = new FStreamImpl[A, B](new ProxyEndPoint(getPublisher(producer)))
            toStream(reader.fork(p => consumer(toStream(p))))
        }

        private class ProxyEndPoint(publisher: Publisher[A]) extends EndPoint[A, B] with Subscriber[A] {
            private val subscribers = mutable.ListBuffer.empty[Subscriber[A]]
            override def subscribe(subscriber: Subscriber[A]): Unit = {
                if(subscribers.isEmpty){
                    publisher.subscribe(this)
                }
                subscribers += subscriber
            }
            override def unsubscribe(subscriber: Subscriber[A]): Unit = {
                subscribers -= subscriber
                if(subscribers.isEmpty){
                    publisher.unsubscribe(this)
                }
            }
            override def write(elem: B): Unit = endPoint.write(elem)
            override def push(elem: A): Unit = subscribers.foreach(_.push(elem))
        }

    }
}

trait FConsumer[A, B, C] extends (FStream[A, B] => Future[C]) {
    def >> [D](cD: => FConsumer[A, B, D])(implicit ec: ExecutionContext): FConsumer[A, B, D] = FConsumer {
        stream => {this.apply(stream) >> cD.apply(stream)}
    }
}

object FConsumer
{
    def apply[A, B, C](fn: FStream[A, B] => Future[C]): FConsumer[A, B, C] = new FConsumer[A, B, C]{
        override def apply(stream: FStream[A, B]): Future[C] = fn(stream)
    }
}