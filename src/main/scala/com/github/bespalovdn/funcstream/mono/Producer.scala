package com.github.bespalovdn.funcstream.mono

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream.ext.FutureExtensions._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.io.StdIn
import scala.util.{Success, Try}

////////////////////////////////////////////////////////////////
trait Producer[A] {
    def get(timeout: Duration = null): Future[A]
    def <=> [B](c: Consumer[A, B])(implicit ec: ExecutionContext): Future[B]
    def transform [B](fn: A => B): Producer[B]
    def filter(fn: A => Boolean): Producer[A]
    def fork(consumer: Producer[A] => Unit): Producer[A]
    def addListener(listener: A => Unit): Producer[A]
}

object Producer
{
    def apply[A](publisher: Publisher[A]): Producer[A] = new ProducerImpl[A](publisher)

    private lazy val logger: Logger = LoggerFactory.getLogger(getClass)

    private[funcstream] class ProducerImpl[A](val publisher: Publisher[A]) extends Producer[A] with Subscriber[A]
    {
        private val available = mutable.Queue.empty[Try[A]]
        private val requested = mutable.Queue.empty[Promise[A]]
        private val listeners = mutable.ArrayBuffer.empty[A => Unit]

        override def push(elem: Try[A]): Unit = {
            elem.foreach(a => listeners.foreach(listener => listener(a)))
            if(requested.nonEmpty)
                requested.dequeue().tryComplete(elem)
            else
                available.enqueue(elem)
        }

        override def get(timeout: Duration = null): Future[A] = {
            if(available.nonEmpty) {
                Future.fromTry(available.dequeue())
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
                override def push(elem: Try[A]): Unit = subscribers.foreach{subscriber =>
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
            new ProducerImpl[B](proxy)
        }

        override def filter(fn: A => Boolean): Producer[A] = {
            val proxy = new Proxy{
                override def push(elem: Try[A]): Unit = elem match {
                    case Success(a) if fn(a) => super.push(elem)
                    case _ => // do nothing
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

////////////////////////////////////////////////////////////////
object StdInTest
{
    class StdReader extends Publisher[String]
    {
        private val subscribers = mutable.ListBuffer.empty[Subscriber[String]]

        override def subscribe(subscriber: Subscriber[String]): Unit = subscribers += subscriber
        override def unsubscribe(subscriber: Subscriber[String]): Unit = subscribers -= subscriber

        private val executorContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
        Future {
            while(true){
                val line = StdIn.readLine()
                subscribers.foreach(_.push(Success(line)))
            }
        }(executorContext)
    }

    def apply(): Unit = {
        import scala.concurrent.ExecutionContext.Implicits.global

        val producer: Producer[String] = Producer(new StdReader)
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

object MonotonicallyIncreasePublisherTest
{
    class Mono extends Publisher[String]
    {
        private val subscribers = mutable.ListBuffer.empty[Subscriber[String]]

        override def subscribe(subscriber: Subscriber[String]): Unit = subscribers += subscriber
        override def unsubscribe(subscriber: Subscriber[String]): Unit = subscribers -= subscriber

        private val executorContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
        Future {
            var nextNumber = 1
            while(true){
                val line = nextNumber.toString
                subscribers.foreach(_.push(Success(line)))
                nextNumber += 1
                Thread.sleep(1000)
            }
        }(executorContext)
    }

    def apply(): Unit ={
        import scala.concurrent.ExecutionContext.Implicits.global

        val producer: Producer[String] = Producer(new Mono)

        def consumer(name: String, nTimes: Int): Consumer[String, Unit] = Consumer{
            p => {
                if(nTimes > 0) {
                    p.get() >>= { str =>
                        println(s"$name: $str")
                        consumer(name, nTimes - 1).apply(p)
                    }
                } else {
                    Future.successful(())
                }
            }
        }

        println("Producer's output:")
        var result: Future[Unit] = producer.fork(p => p <=> (consumer("B", 3) >> consumer("C", 3))) <=> consumer("A", 10)
        Await.ready(result, Duration.Inf)
        Thread.sleep(3000)
        result = producer.fork(p => p <=> (consumer("B", 3) >> consumer("C", 3))) <=> consumer("A", 10)
        Await.ready(result, Duration.Inf)

        println("DONE")
    }
}