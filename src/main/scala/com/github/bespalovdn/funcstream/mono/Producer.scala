package com.github.bespalovdn.funcstream.mono

import java.util.concurrent.TimeoutException

import com.github.bespalovdn.funcstream.Resource
import com.github.bespalovdn.funcstream.config.ReadTimeout
import com.github.bespalovdn.funcstream.exception.ConnectionClosedException
import com.github.bespalovdn.funcstream.ext.FutureUtils._
import com.github.bespalovdn.funcstream.ext.TimeoutSupport
import com.github.bespalovdn.funcstream.impl.PublisherProxy

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}


trait Producer[+A] extends Resource
{
    def get()(implicit timeout: ReadTimeout): Future[A]
    def getWithFilter[B](filter: A => Option[B])(implicit timeout: ReadTimeout): Future[B]
    def getOrStash[B](reader: A => Option[Future[B]])(implicit timeout: ReadTimeout): Future[B]
    def pipeTo [B](c: Consumer[A, B]): Future[B]
    def ==> [B](c: Consumer[A, B]): Future[B] = pipeTo(c)
    def filter(fn: A => Boolean): Producer[A]
    def filterNot(fn: A => Boolean): Producer[A]
    def transform [B](fn: A => B): Producer[B]
    def transformWithFilter [B](fn: A => Option[B]): Producer[B]
    def fork(): Producer[A]
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
            val stashed = mutable.Queue.empty[A]
        }

        private def connectionClosed[B]: Future[B] = closed >> fail(new ConnectionClosedException)

        override def push(elem: Try[A]): Unit = {
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

        override def get()(implicit timeout: ReadTimeout): Future[A] =
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

        override def getWithFilter[B](filter: A => Option[B])(implicit timeout: ReadTimeout): Future[B] = {
            val totalTimeout = waitFor(timeout) >> fail(new TimeoutException(timeout.toString))
            def tryRead(): Future[B] = for {
                a <- get() <|> totalTimeout
                b <- filter(a) match {
                    case Some(b) => success(b)
                    case None => tryRead()
                }
            } yield b
            tryRead()
        }

        override def getOrStash[B](reader: A => Option[Future[B]])(implicit timeout: ReadTimeout): Future[B] = {
            val totalTimeout = waitFor(timeout) >> fail(new TimeoutException(timeout.toString))
            def tryRead(): Future[B] = for {
                a <- get() <|> totalTimeout
                b <- reader(a) match {
                    case Some(fB) => fB
                    case None =>
                        stash(a)
                        tryRead()
                }
            } yield b
            tryRead() andThen { case _ => unstashAll() }
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

        override def close(): Future[Unit] = publisher.close()
        override def closed: Future[Unit] = publisher.closed

        private def stash(value: A): Unit = elements.synchronized {
            elements.stashed.enqueue(value)
        }

        private def unstashAll(): Unit = elements.synchronized {
            if(elements.stashed.nonEmpty){
                val elem = elements.stashed.dequeue()
                def enqueue(): Unit = {
                    if(elements.requested.nonEmpty) {
                        val completed = elements.requested.dequeue().tryComplete(Success(elem))
                        if(!completed) {
                            enqueue() // to handle timed out requests
                        }
                    } else {
                        elements.available.enqueue(Success(elem))
                    }
                }
                enqueue()
                unstashAll()
            }
        }
    }
}
