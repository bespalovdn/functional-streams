package com.github.bespalovdn.funcstream.v2

import com.github.bespalovdn.funcstream.ext.FutureExtensions._

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}

trait EndPoint[A, B] extends Publisher[A] with Subscriber[B]

trait FStream[A, B]{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B, timeout: Duration = null): Future[Unit]
    def <=> [C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C]
}

object FStream
{
    def apply[A, B](endPoint: EndPoint[A, B]): FStream[A, B] = {
        val impl = new FStreamImpl[A, B](endPoint)
        impl.subscribe(endPoint)
        impl
    }

    private class FStreamImpl[A, B](publisher: Publisher[A])
        extends FStream[A, B]
        with Publisher[B] with Subscriber[A]
    {
        private val subscribersA = mutable.ListBuffer.empty[Subscriber[A]]
        private val subscribersB = mutable.ListBuffer.empty[Subscriber[B]]

        private object readQueue{
            val available = mutable.Queue.empty[A]
            val requested = mutable.Queue.empty[Promise[A]]
        }

        override def subscribe(subscriber: Subscriber[B]): Unit = {
            if(subscribersB.isEmpty){
                publisher.subscribe(this)
            }
            subscribersB += subscriber
        }

        override def unsubscribe(subscriber: Subscriber[B]): Unit = {
            subscribersB -= subscriber
            if(subscribersB.isEmpty){
                publisher.unsubscribe(this)
            }
        }

        override def push(elem: A): Unit = {
            if(readQueue.requested.nonEmpty)
                readQueue.requested.dequeue().trySuccess(elem)
            else
                readQueue.available.enqueue(elem)
        }

        override def read(timeout: Duration): Future[A] = {
            if(readQueue.available.nonEmpty) {
                Future.successful(readQueue.available.dequeue())
            } else{
                val p = Promise[A]
                readQueue.requested.enqueue(p)
                p.future
            }
        }

        override def write(elem: B, timeout: Duration): Future[Unit] = {
            subscribersB.foreach(_.push(elem))
            success()
        }

        override def <=>[C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C] = ???
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