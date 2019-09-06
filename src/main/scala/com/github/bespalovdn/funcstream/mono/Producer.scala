package com.github.bespalovdn.funcstream.mono

import java.util.concurrent.TimeoutException

import com.github.bespalovdn.funcstream.Resource
import com.github.bespalovdn.funcstream.config.ReadTimeout
import com.github.bespalovdn.funcstream.exception.ConnectionClosedException
import com.github.bespalovdn.funcstream.ext.FutureUtils._
import com.github.bespalovdn.funcstream.ext.TimeoutSupport
import com.github.bespalovdn.funcstream.impl.PublisherProxy
import org.slf4j.LoggerFactory

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
    def filter(fn: A => Boolean, name: String = "NONAME"): Producer[A]
    def filterNot(fn: A => Boolean, name: String = "NONAME"): Producer[A]
    def transform [B](fn: A => B, name: String = "NONAME"): Producer[B]
    def transformWithFilter [B](fn: A => Option[B], name: String = "NONAME"): Producer[B]
    def fork(name: String = "NONAME"): Producer[A]
}

object Producer
{
    private lazy val logger = LoggerFactory.getLogger(getClass)

    def apply[A](publisher: Publisher[A] with Resource, name: String = "NONAME"): Producer[A] = new ProducerImpl[A](name, publisher)

    private[funcstream] class ProducerImpl[A](name: String, val publisher: Publisher[A] with Resource)
        extends Producer[A]
            with Subscriber[A]
            with TimeoutSupport
    {
        private def log(msg: String): Unit = {} //logger.warn(name + ": " + msg)

        private object elements {
            val available = mutable.Queue.empty[Try[A]]
            val requested = mutable.Queue.empty[Promise[A]]
            val stashed = mutable.Queue.empty[A]
        }

        private def connectionClosed[B]: Future[B] = closed >> fail(new ConnectionClosedException)

        override def push(elem: Try[A]): Unit = {
            log("FS: push started with elem: " + elem)
            elements.synchronized {
                if(elements.requested.isEmpty){
                    log("FS: Element is being added into AVAILABLE queue. Elem: " + elem)
                    elements.available.enqueue(elem)
                } else {
                    // Try complete requested elements.
                    // Request may fail completion, for instance it already complete by timeout.
                    // In this case we try to complete next requested element.
                    var isEmpty = false
                    var completed = false
                    do{
                        isEmpty = elements.requested.isEmpty
                        completed = if(!isEmpty) {
                            log("FS: Requested element is being complete by: " + elem)
                            elements.requested.dequeue().tryComplete(elem)
                        } else false
                    }while(!isEmpty && !completed)
                    // Finally, if no one requested element complete, we add an element into list of available items:
                    if(!completed){
                        log("FS: Element is being added into AVAILABLE queue, due to `completed` status. Elem: " + elem)
                        elements.available.enqueue(elem)
                    }
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

        override def transform [B](fn: A => B, name: String): Producer[B] = {
            val proxy = new PublisherProxy[A, B]{
                override def upstream: Publisher[A] with Resource = publisher
                override def push(elem: Try[A]): Unit = {
                    val transformed: Try[B] = elem.map(fn)
                    log("transform: push elem: " + transformed)
                    forEachSubscriber(_.push(transformed))
                }
            }
            new ProducerImpl[B](name, proxy)
        }

        override def transformWithFilter[B](fn: (A) => Option[B], name: String): Producer[B] = {
            val proxy = new PublisherProxy[A, B]{
                override def upstream: Publisher[A] with Resource = publisher
                override def push(elem: Try[A]): Unit = {
                    val transformed: Try[Option[B]] = elem.map(fn)
                    transformed match {
                        case Success(Some(b)) =>
                            log("transformWithFilter: push elem: " + transformed)
                            forEachSubscriber(_.push(Success(b)))
                        case Success(None) => // skip
                            log("transformWithFilter: didn't pass the filter for elem: " + elem)
                        case Failure(t) =>
                            log("transformWithFilter: failed for elem: " + elem)
                            forEachSubscriber(_.push(Failure(t)))
                    }
                }
            }
            new ProducerImpl[B](name, proxy)
        }

        override def filter(fn: A => Boolean, name: String): Producer[A] = {
            val proxy = new PublisherProxy[A, A]{
                override def upstream: Publisher[A] with Resource = publisher
                override def push(elem: Try[A]): Unit = {
                    elem match {
                        case Success(a) if fn(a) =>
                            log("filter: push further for elem: " + elem)
                            forEachSubscriber(_.push(elem))
                        case _ => // do nothing
                            log("filter: elem didn't pass filter: " + elem)
                    }
                }
            }
            new ProducerImpl[A](name, proxy)
        }

        override def filterNot(fn: A => Boolean, name: String): Producer[A] = filter(a => !fn(a), name)

        override def fork(name: String): Producer[A] = new ProducerImpl[A](name, publisher)

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
