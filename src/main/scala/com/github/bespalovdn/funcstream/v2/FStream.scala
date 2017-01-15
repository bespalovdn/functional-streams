package com.github.bespalovdn.funcstream.v2

import java.util.concurrent.atomic.AtomicInteger

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
    def apply[A, B](endPoint: EndPoint[A, B]): FStream[A, B] = new FStreamImpl[A, B](endPoint)

    private class FStreamImpl[A, B](endPoint: EndPoint[A, B])
        extends FStream[A, B]
    {
        private object readQueue{
            val available = mutable.Queue.empty[A]
            val requested = mutable.Queue.empty[Promise[A]]
        }

        private object upStream extends Publisher[B] with Subscriber[A] {
            val upSubscribers = mutable.ListBuffer.empty[Subscriber[B]]

            override def subscribe(subscriber: Subscriber[B]): Unit = upSubscribers += subscriber

            override def unsubscribe(subscriber: Subscriber[B]): Unit = upSubscribers -= subscriber

            override def push(elem: A): Unit = downStream.write(elem)

            def write(elem: B): Unit = upSubscribers.foreach(_.push(elem))
        }

        // endpoint will be constantly subscribed to upStream, to be able to write to endpoint unconditionally
        upStream.subscribe(endPoint)

        private object downStream extends Subscriber[B] {
            val subscribersCount = new AtomicInteger(0)

            def subscribe(): Unit = {
                if(subscribersCount.getAndIncrement() == 0){
                    endPoint.subscribe(upStream) // to read from endpoint
                }
            }

            def unsubscribe(): Unit = {
                if(subscribersCount.decrementAndGet() == 0){
                    endPoint.unsubscribe(upStream)
                }
            }

            override def push(elem: B): Unit = upStream.write(elem)

            def write(elem: A): Unit = {
                if(readQueue.requested.nonEmpty)
                    readQueue.requested.dequeue().trySuccess(elem)
                else
                    readQueue.available.enqueue(elem)
            }
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
            upStream.write(elem)
            success()
        }

        override def <=>[C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C] = {
            downStream.subscribe()
            val f = c.apply(this)
            f.onComplete(_ => downStream.unsubscribe())
            f
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