package com.github.bespalovdn.funcstream.mono

import com.github.bespalovdn.funcstream.ext.TimeoutSupport
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Success, Try}

trait Producer[A] {
    def get(timeout: Duration = null): Future[A]
    def consume [B](c: Consumer[A, B])(implicit ec: ExecutionContext): Future[B]
    def transform [B](fn: A => B): Producer[B]
    def filter(fn: A => Boolean): Producer[A]
    def fork(consumer: Producer[A] => Unit): Producer[A]
    def addListener(listener: A => Unit): Producer[A]
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
        private val listeners = mutable.ArrayBuffer.empty[A => Unit]

        override def push(elem: Try[A]): Unit = {
            notifyListeneres(elem)
            elements.synchronized {
                if (elements.requested.nonEmpty) {
                    val completed = elements.requested.dequeue().tryComplete(elem)
                    if(!completed) {
                        push(elem) // to handle timed out requests
                    }
                }else {
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

        override def consume [B](c: Consumer[A, B])(implicit ec: ExecutionContext): Future[B] = {
            publisher.subscribe(this)
            val f = c.apply(this)
            f.onComplete(_ => publisher.unsubscribe(this))
            f
        }

        override def transform [B](fn: A => B): Producer[B] = {
            val proxy = new Publisher[B] with Subscriber[A]{
                private val subscribers = mutable.ListBuffer.empty[Subscriber[B]]
                override def subscribe(subscriber: Subscriber[B]): Unit = {
                    if(subscribers.isEmpty){
                        publisher.subscribe(this)
                    }
                    subscribers += subscriber
                }
                override def unsubscribe(subscriber: Subscriber[B]): Unit = {
                    subscribers -= subscriber
                    if(subscribers.isEmpty){
                        publisher.unsubscribe(this)
                    }
                }
                override def push(elem: Try[A]): Unit = {
                    notifyListeneres(elem)
                    subscribers.foreach{subscriber =>
                        try{
                            val transformed: Try[B] = elem.map(fn)
                            subscriber.push(transformed)
                        }catch{
                            case t: Throwable =>
                                logger.error(s"Failed to transform value: [$elem]. Cause: [%s]" format t.getMessage)
                                throw t
                        }
                    }
                }
            }
            new ProducerImpl[B](proxy)
        }

        override def filter(fn: A => Boolean): Producer[A] = {
            val proxy = new Proxy{
                override def push(elem: Try[A]): Unit = {
                    notifyListeneres(elem)
                    elem match {
                        case Success(a) if fn(a) => super.push(elem)
                        case _ => // do nothing
                    }
                }
            }
            new ProducerImpl[A](proxy)
        }

        override def fork(consumer: Producer[A] => Unit): Producer[A] = {
            val p1 = new ProducerImpl[A](new Proxy)
            val p2 = new ProducerImpl[A](new Proxy)
            consumer(p1)
            p2
        }

        override def addListener(listener: (A) => Unit): Producer[A] = {
            listeners.synchronized{ listeners += listener }
            this
        }

        private def notifyListeneres(elem: Try[A]): Unit ={
            elem.foreach(a => listeners.synchronized{ listeners.foreach(listener => listener(a)) })
        }

        private class Proxy extends Publisher[A] with Subscriber[A]{
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
            override def push(elem: Try[A]): Unit = subscribers.foreach(_.push(elem))
        }
    }
}
