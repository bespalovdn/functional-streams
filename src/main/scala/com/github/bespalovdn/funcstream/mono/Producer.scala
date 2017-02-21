package com.github.bespalovdn.funcstream.mono

import java.util.concurrent.atomic.AtomicBoolean

import com.github.bespalovdn.funcstream.ext.TimeoutSupport
import com.github.bespalovdn.funcstream.impl.PublisherProxy
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.{Success, Try}

trait Producer[A] {
    def get(timeout: Duration = null): Future[A]
    def pipeTo [B](c: Consumer[A, B]): Future[B]
    def ==> [B](c: Consumer[A, B]): Future[B] = pipeTo(c)
    def transform [B](fn: A => B): Producer[B]
    def filter(fn: A => Boolean): Producer[A]
    def filterNot(fn: A => Boolean): Producer[A]
    def fork(): Producer[A]
    def addListener(listener: A => Unit): Producer[A]

    def preSubscribe(keepSubscribed: Boolean): Unit
    def preSubscriptionStop(): Unit
}

object Producer
{
    def apply[A](publisher: Publisher[A]): Producer[A] = new ProducerImpl[A](publisher)

    private lazy val logger: Logger = LoggerFactory.getLogger(getClass)

    private[funcstream] class ProducerImpl[A](val publisher: Publisher[A])
        extends Producer[A]
        with Subscriber[A]
        with TimeoutSupport
    {
        private object elements {
            val available = mutable.Queue.empty[Try[A]]
            val requested = mutable.Queue.empty[Promise[A]]
        }
        private var listeners = Vector.empty[A => Unit]
        private var pinSubscribed: Option[Boolean] = None //Some(true) to keep subscribed, Some(false) to unsubscribe together with first consumer
        private val hasActiveConsumer = new AtomicBoolean(false)

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
                withTimeout(timeout)(p.future)
            }
        }

        override def pipeTo [B](c: Consumer[A, B]): Future[B] = {
            import scala.concurrent.ExecutionContext.Implicits.global
            hasActiveConsumer.set(true)
            val f = c.consume(this)
            publisher.subscribe(this)
            f.onComplete{ _ =>
                hasActiveConsumer.set(false)
                if(!pinSubscribed.getOrElse(false))
                    publisher.unsubscribe(this)
            }
            f
        }

        override def preSubscribe(keepSubscribed: Boolean): Unit = {
            this.pinSubscribed = Some(keepSubscribed)
            publisher.subscribe(this)
        }

        override def preSubscriptionStop(): Unit = {
            pinSubscribed = None
            if(!hasActiveConsumer.get())
                publisher.unsubscribe(this)
        }

        override def transform [B](fn: A => B): Producer[B] = {
            val proxy = new PublisherProxy[A, B]{
                override def upstream: Publisher[A] = publisher
                override def push(elem: Try[A]): Unit = {
                    notifyListeners(elem)
                    forEachSubscriber{ subscriber =>
                        try{
                            val transformed: Try[B] = elem.map(fn)
                            subscriber.push(transformed)
                        }catch{
                            case t: Throwable =>
                                logger.error(s"Failed to transform value: [$elem].", t)
                                throw t
                        }
                    }
                }
            }
            new ProducerImpl[B](proxy)
        }

        override def filter(fn: A => Boolean): Producer[A] = {
            val proxy = new Proxy(publisher){
                override def push(elem: Try[A]): Unit = {
                    notifyListeners(elem)
                    elem match {
                        case Success(a) if fn(a) => super.push(elem)
                        case _ => // do nothing
                    }
                }
            }
            new ProducerImpl[A](proxy)
        }

        override def filterNot(fn: A => Boolean): Producer[A] = filter(a => !fn(a))

        override def fork(): Producer[A] = new ProducerImpl[A](new Proxy(publisher))

        override def addListener(listener: A => Unit): Producer[A] = {
            listeners :+= listener
            this
        }

        private def notifyListeners(elem: Try[A]): Unit = {
            elem.foreach(a => listeners.foreach(listener => listener(a)))
        }

        private class Proxy(val upstream: Publisher[A]) extends PublisherProxy[A, A]{
            override def push(elem: Try[A]): Unit = forEachSubscriber(_.push(elem))
        }
    }
}
