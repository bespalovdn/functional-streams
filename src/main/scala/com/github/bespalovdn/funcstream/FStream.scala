package com.github.bespalovdn.funcstream

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream.ext.FutureExtensions._
import com.github.bespalovdn.funcstream.mono.Producer.ProducerImpl
import com.github.bespalovdn.funcstream.mono.{Consumer, Producer, Publisher, Subscriber}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.StdIn
import scala.util.{Success, Try}

trait FStream[A, B]{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]
    def <=> [C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C]
    def transform [C, D](transOut: A => C, transIn: D => B): FStream[C, D]
    def filter(fn: A => Boolean): FStream[A, B]
    def fork(consumer: FStream[A, B] => Unit): FStream[A, B]
    def addListener(listener: A => Unit): FStream[A, B]
}

object FStream
{
    def apply[A, B](endPoint: EndPoint[A, B]): FStream[A, B] = new FStreamImpl[A, B](endPoint)

    private class FStreamImpl[A, B](endPoint: EndPoint[A, B])
        extends FStream[A, B]
    {
        private val reader: Producer[A] = Producer(endPoint)

        override def read(timeout: Duration): Future[A] = reader.get(timeout)

        override def write(elem: B): Future[Unit] = {
            endPoint.synchronized{ endPoint.write(elem) }
            success()
        }

        override def <=>[C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C] = {
            val consumer = Consumer[A, C] { _ => c.apply(this) }
            reader consume consumer
        }

        override def transform [C, D](transOut: A => C, transIn: D => B): FStream[C, D] = {
            val transformed: Producer[C] = reader.transform(transOut)
            producer2stream(transformed, transIn)
        }

        override def filter(fn: (A) => Boolean): FStream[A, B] = {
            val filtered = reader.filter(fn)
            producer2stream(filtered, identity)
        }

        override def fork(consumer: FStream[A, B] => Unit): FStream[A, B] = {
            producer2stream(reader.fork(p => consumer(producer2stream(p, identity))), identity)
        }

        override def addListener(listener: (A) => Unit): FStream[A, B] = {
            reader.addListener(listener)
            this
        }

        private def producer2stream[C, D](producer: Producer[C], transIn: D => B): FStream[C, D] = {
            def getPublisher(producer: Producer[C]): Publisher[C] = producer.asInstanceOf[ProducerImpl[C]].publisher
            def toStream(producer: Producer[C]): FStream[C, D] = new FStreamImpl[C, D](new ProxyEndPoint(getPublisher(producer), transIn))
            toStream(producer)
        }

        private class ProxyEndPoint[C, D](publisher: Publisher[C], transIn: D => B) extends EndPoint[C, D] with Subscriber[C] {
            private val subscribers = mutable.ListBuffer.empty[Subscriber[C]]
            override def subscribe(subscriber: Subscriber[C]): Unit = subscribers.synchronized{
                if(subscribers.isEmpty){
                    publisher.subscribe(this)
                }
                subscribers += subscriber
            }
            override def unsubscribe(subscriber: Subscriber[C]): Unit = subscribers.synchronized{
                subscribers -= subscriber
                if(subscribers.isEmpty){
                    publisher.unsubscribe(this)
                }
            }
            override def write(elem: D): Unit = endPoint.synchronized{ endPoint.write(transIn(elem)) }
            override def push(elem: Try[C]): Unit = subscribers.synchronized{ subscribers.foreach(_.push(elem)) }
        }

    }
}

////////////////////////////////////////////////////////////////
object FStreamStdInTest
{
    class StdEndpoint extends EndPoint[String, String]{
        private val executorContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))
        private val subscribers = mutable.ListBuffer.empty[Subscriber[String]]

        override def subscribe(subscriber: Subscriber[String]): Unit = subscribers += subscriber
        override def unsubscribe(subscriber: Subscriber[String]): Unit = subscribers -= subscriber

        override def write(elem: String): Unit = Future {
            println(elem)
        }(executorContext)

        Future {
            while(true){
                val line = StdIn.readLine()
                subscribers.foreach(_.push(Success(line)))
            }
        }(executorContext)
    }

    def apply(): Unit = {
        import scala.concurrent.ExecutionContext.Implicits.global

        val stream: FStream[String, String] = FStream(new StdEndpoint)
        val toInt: String => Int = _.toInt // transformer
        val even: Int => Boolean = i => i % 2 == 0 // filter
        val consumer: FConsumer[Int, String, Int] = FConsumer { stream =>
            for {
                _ <- stream.write("Enter some even number:")
                a <- stream.read()
                _ <- stream.write("Enter some even number once again:")
                b <- stream.read()
            } yield a + b
        }
        val result: Future[Int] = stream.transform(toInt, identity[String]).filter(even) <=> consumer
        println("SUM OF EVENS IS: " + Await.result(result, Duration.Inf))
    }
}

object FStreamForkTest
{
    class Mono extends EndPoint[String, String]
    {
        private val subscribers = mutable.ListBuffer.empty[Subscriber[String]]

        override def subscribe(subscriber: Subscriber[String]): Unit = subscribers += subscriber
        override def unsubscribe(subscriber: Subscriber[String]): Unit = subscribers -= subscriber

        override def write(elem: String): Unit = Future {
            println(elem)
        }(executorContext)

        private val executorContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
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

        val stream: FStream[String, String] = FStream(new Mono)

        def consumer(name: String, nTimes: Int): FConsumer[String, String, Unit] = FConsumer{
            stream => {
                if(nTimes > 0) {
                    stream.read() >>= { str =>
                        stream.write(s"$name: $str")
                        consumer(name, nTimes - 1).apply(stream)
                    }
                } else {
                    Future.successful(())
                }
            }
        }

        println("Producer's output:")
        var result: Future[Unit] = stream.fork(p => p <=> (consumer("B", 3) >> consumer("C", 3))) <=> consumer("A", 10)
        Await.ready(result, Duration.Inf)
        Thread.sleep(3000)
        result = stream.fork(p => p <=> (consumer("B", 3) >> consumer("C", 3))) <=> consumer("A", 10)
        Await.ready(result, Duration.Inf)

        println("DONE")
    }
}