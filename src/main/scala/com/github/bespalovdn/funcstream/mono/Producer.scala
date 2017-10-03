package com.github.bespalovdn.funcstream.mono

import com.github.bespalovdn.funcstream.ext.TimeoutSupport
import com.github.bespalovdn.funcstream.impl.PublisherProxy

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.{Success, Try}

trait Producer[+A] {
    def get(timeout: Duration = null): Future[A]
    def pipeTo [B](c: Consumer[A, B]): Future[B]
    def ==> [B](c: Consumer[A, B]): Future[B] = pipeTo(c)
    def transform [B](fn: A => B): Producer[B]
    def filter(fn: A => Boolean): Producer[A]
    def filterNot(fn: A => Boolean): Producer[A]
    def fork(): Producer[A]
    def addListener[A0 >: A](listener: Try[A0] => Unit): Producer[A]
}

object Producer
{
    def apply[A](publisher: Publisher[A]): Producer[A] = new ProducerImpl[A](publisher)

    private[funcstream] class ProducerImpl[A](val publisher: Publisher[A])
        extends Producer[A]
        with Subscriber[A]
        with TimeoutSupport
    {
        private object elements {
            val available = mutable.Queue.empty[Try[A]]
            val requested = mutable.Queue.empty[Promise[A]]
        }
        private var listeners = Vector.empty[Try[A] => Unit]

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

        override def get(timeout: Duration = null): Future[A] = elements.synchronized {
            if(elements.available.nonEmpty) {
                Future.fromTry(elements.available.dequeue())
            } else{
                val p = Promise[A]
                elements.requested.enqueue(p)
                withTimeout(timeout)(p)
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
                override def upstream: Publisher[A] = publisher
                override def push(elem: Try[A]): Unit = {
                    notifyListeners(elem)
                    val transformed: Try[B] = elem.map(fn)
                    forEachSubscriber(_.push(transformed))
                }
            }
            new ProducerImpl[B](proxy)
        }

        override def filter(fn: A => Boolean): Producer[A] = {
            val proxy = new PublisherProxy[A, A]{
                override def upstream: Publisher[A] = publisher
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
    }
}
