package com.github.bespalovdn.funcstream.v2

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream.ext.FutureExtensions._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.io.StdIn

trait Publisher[A]{
    def subscribe(subscriber: Subscriber[A])
    def unsubscribe(subscriber: Subscriber[A])
}

trait Subscriber[A]{
    def push(elem: A)
}

////////////////////////////////////////////////////////////////
trait Producer[A] {
    def get(timeout: Duration = null): Future[A]
    def <=> [B](c: Consumer[A, B])(implicit ec: ExecutionContext): Future[B]
    def transform [B](fn: A => B): Producer[B]
    def filter(fn: A => Boolean): Producer[A]
    def fork(consumer: Producer[A] => Unit): Producer[A]
}

object Producer
{
    def apply[A](publisher: Publisher[A]): Producer[A] = new ProducerImpl[A](publisher)

    private lazy val logger: Logger = LoggerFactory.getLogger(getClass)

    private class ProducerImpl[A](publisher: Publisher[A]) extends Producer[A] with Subscriber[A]
    {
        private val available = mutable.Queue.empty[A]
        private val requested = mutable.Queue.empty[Promise[A]]

        override def push(elem: A): Unit = {
            if(requested.nonEmpty)
                requested.dequeue().trySuccess(elem)
            else
                available.enqueue(elem)
        }

        override def get(timeout: Duration = null): Future[A] = {
            if(available.nonEmpty) {
                Future.successful(available.dequeue())
            } else{
                val p = Promise[A]
                requested.enqueue(p)
                p.future
            }
        }

        override def <=> [B](c: Consumer[A, B])(implicit ec: ExecutionContext): Future[B] = {
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
                override def push(elem: A): Unit = subscribers.foreach{subscriber =>
                    try{
                        val transformed: B = fn(elem)
                        subscriber.push(transformed)
                    }catch{
                        case t: Throwable => logger.error("Failed to transform value: " + elem)
                    }
                }
            }
            new ProducerImpl[B](proxy)
        }

        override def filter(fn: A => Boolean): Producer[A] = {
            val proxy = new Proxy{
                override def push(elem: A): Unit = if(fn(elem)){
                    super.push(elem)
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
            override def push(elem: A): Unit = subscribers.foreach(_.push(elem))
        }
    }
}


trait Consumer[A, B] extends (Producer[A] => Future[B]) {
    def >> [C](cC: => Consumer[A, C])(implicit ec: ExecutionContext): Consumer[A, C] = Consumer {
        p => {this.apply(p) >> cC.apply(p)}
    }
}

object Consumer
{
    def apply[A, B](fn: Producer[A] => Future[B]): Consumer[A, B] = new Consumer[A, B]{
        override def apply(p: Producer[A]): Future[B] = fn(p)
    }
}

////////////////////////////////////////////////////////////////
object StdInTest
{
    import scala.concurrent.ExecutionContext.Implicits.global

    class stdReader extends Publisher[String]
    {
        private val subscribers = mutable.ListBuffer.empty[Subscriber[String]]

        override def subscribe(subscriber: Subscriber[String]): Unit = subscribers += subscriber
        override def unsubscribe(subscriber: Subscriber[String]): Unit = subscribers -= subscriber

        private val executorContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
        Future {
            while(true){
                val line = StdIn.readLine()
                subscribers.foreach(_.push(line))
            }
        }(executorContext)
    }

    def apply(): Unit = {
        val producer: Producer[String] = Producer(new stdReader)
        val toInt: String => Int = _.toInt // transformer
        val even: Int => Boolean = i => i % 2 == 0 // filter
        val consumer: Consumer[Int, Int] = Consumer{
            p => for {
                a <- p.get()
                b <- p.get()
            } yield a + b
        }

        println("Input some numbers:")
        val result: Future[Int] = producer.transform(toInt).filter(even) <=> consumer
        println("SUM OF EVENS IS: " + Await.result(result, Duration.Inf))
    }
}