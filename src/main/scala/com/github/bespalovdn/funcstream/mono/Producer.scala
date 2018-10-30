package com.github.bespalovdn.funcstream.mono

import com.github.bespalovdn.funcstream.Resource
import com.github.bespalovdn.funcstream.exception.ConnectionClosedException
import com.github.bespalovdn.funcstream.ext.FutureUtils._
import com.github.bespalovdn.funcstream.ext.TimeoutSupport
import com.github.bespalovdn.funcstream.impl.PublisherProxy

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

trait Producer[+A] extends Resource
{
    def get(timeout: Duration = null): Future[A]
    def pipeTo [B](c: Consumer[A, B]): Future[B]
    def ==> [B](c: Consumer[A, B]): Future[B] = pipeTo(c)
    def filter(fn: A => Boolean): Producer[A]
    def filterNot(fn: A => Boolean): Producer[A]
    def transform [B](fn: A => B): Producer[B]
    def transformWithFilter [B](fn: A => Option[B]): Producer[B]
    def fork(): Producer[A]
    def addListener[A0 >: A](listener: Try[A0] => Unit): Producer[A]
}

object Producer
{
    def apply[A](publisher: Publisher[A] with Resource): Producer[A] = new ProducerImpl[A](publisher)

    private[funcstream] class ProducerImpl[A](val publisher: Publisher[A] with Resource)
        extends Producer[A]
        with Subscriber[A]
        with TimeoutSupport
    {
        private object elements {
            val available = mutable.Queue.empty[Try[A]]
            val requested = mutable.Queue.empty[Promise[A]]
        }
        private var listeners = Vector.empty[Try[A] => Unit]
        private def connectionClosed[B]: Future[B] = closed >> fail(new ConnectionClosedException)

        override def push(elem: Try[A]): Unit = {
            notifyListeners(elem)
            elements.synchronized {
                if(elements.requested.nonEmpty) {
                    val completed = elements.requested.dequeue().tryComplete(elem)
                    if(!completed) {
                        push(elem) // to handle timed out requests
                    }
                } else {
                    elements.available.enqueue(elem)
                }
            }
        }

        override def get(timeout: Duration = null): Future[A] =
            if(closed.isCompleted) fail(new ConnectionClosedException)
            else {
                elements.synchronized {
                    if (elements.available.nonEmpty) {
                        Future.fromTry(elements.available.dequeue()) <|> connectionClosed
                    } else {
                        val p = Promise[A]
                        elements.requested.enqueue(p)
                        withTimeout(timeout)(p) <|> connectionClosed
                    }
                }
            }

        override def pipeTo [B](c: Consumer[A, B]): Future[B] = {
            import scala.concurrent.ExecutionContext.Implicits.global
            publisher.subscribe(this)
            c.consume(this) andThen {
                case _ => publisher.unsubscribe(this)
            }
        }

        override def transform [B](fn: A => B): Producer[B] = {
            val proxy = new PublisherProxy[A, B]{
                override def upstream: Publisher[A] with Resource = publisher
                override def push(elem: Try[A]): Unit = {
                    notifyListeners(elem)
                    val transformed: Try[B] = elem.map(fn)
                    forEachSubscriber(_.push(transformed))
                }
            }
            new ProducerImpl[B](proxy)
        }

        override def transformWithFilter[B](fn: (A) => Option[B]): Producer[B] = {
            val proxy = new PublisherProxy[A, B]{
                override def upstream: Publisher[A] with Resource = publisher
                override def push(elem: Try[A]): Unit = {
                    notifyListeners(elem)
                    val transformed: Try[Option[B]] = elem.map(fn)
                    transformed match {
                        case Success(Some(b)) => forEachSubscriber(_.push(Success(b)))
                        case Success(None) => // skip
                        case Failure(t) => forEachSubscriber(_.push(Failure(t)))
                    }
                }
            }
            new ProducerImpl[B](proxy)
        }

        override def filter(fn: A => Boolean): Producer[A] = {
            val proxy = new PublisherProxy[A, A]{
                override def upstream: Publisher[A] with Resource = publisher
                override def push(elem: Try[A]): Unit = {
                    notifyListeners(elem)
                    elem match {
                        case Success(a) if fn(a) => forEachSubscriber(_.push(elem))
                        case _ => // do nothing
                    }
                }
            }
            new ProducerImpl[A](proxy)
        }

        override def filterNot(fn: A => Boolean): Producer[A] = filter(a => !fn(a))

        override def fork(): Producer[A] = new ProducerImpl[A](publisher)

        override def addListener[A0 >: A](listener: Try[A0] => Unit): Producer[A] = {
            listeners :+= listener
            this
        }

        private def notifyListeners(elem: Try[A]): Unit = {
            listeners.foreach(listener => listener(elem))
        }

        override def close(): Future[Unit] = publisher.close()
        override def closed: Future[Unit] = publisher.closed
    }
}
