package com.github.bespalovdn.funcstream.v2

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream.ext.FutureExtensions._
import com.github.bespalovdn.funcstream.v2.Producer.ProducerImpl

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.StdIn

trait EndPoint[A, B] extends Publisher[A]{
    def write(elem: B): Unit
}

trait FStream[A, B]{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]
    def <=> [C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C]
    def transform [C](fn: A => C): FStream[C, B]
    def filter(fn: A => Boolean): FStream[A, B]
    def fork(consumer: FStream[A, B] => Unit): FStream[A, B]
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
            endPoint.write(elem)
            success()
        }

        override def <=>[C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C] = {
            val consumer = Consumer[A, C] { _ => c.apply(this) }
            reader <=> consumer
        }

        override def transform[C](fn: (A) => C): FStream[C, B] = {
            val transformed = reader.transform(fn)
            producer2stream(transformed)
        }

        override def filter(fn: (A) => Boolean): FStream[A, B] = {
            val filtered = reader.filter(fn)
            producer2stream(filtered)
        }

        override def fork(consumer: FStream[A, B] => Unit): FStream[A, B] = {
            producer2stream(reader.fork(p => consumer(producer2stream(p))))
        }

        private def producer2stream[C](producer: Producer[C]): FStream[C, B] = {
            def getPublisher(producer: Producer[C]): Publisher[C] = producer.asInstanceOf[ProducerImpl[C]].publisher
            def toStream(producer: Producer[C]): FStream[C, B] = new FStreamImpl[C, B](new ProxyEndPoint(getPublisher(producer)))
            toStream(producer)
        }

        private class ProxyEndPoint[C](publisher: Publisher[C]) extends EndPoint[C, B] with Subscriber[C] {
            private val subscribers = mutable.ListBuffer.empty[Subscriber[C]]
            override def subscribe(subscriber: Subscriber[C]): Unit = {
                if(subscribers.isEmpty){
                    publisher.subscribe(this)
                }
                subscribers += subscriber
            }
            override def unsubscribe(subscriber: Subscriber[C]): Unit = {
                subscribers -= subscriber
                if(subscribers.isEmpty){
                    publisher.unsubscribe(this)
                }
            }
            override def write(elem: B): Unit = endPoint.write(elem)
            override def push(elem: C): Unit = subscribers.foreach(_.push(elem))
        }

    }
}

trait FConsumer[A, B, C] extends (FStream[A, B] => Future[C]) {
    def >> [D](cD: => FConsumer[A, B, D])(implicit ec: ExecutionContext): FConsumer[A, B, D] = FConsumer {
        stream => {this.apply(stream) >> cD.apply(stream)}
    }
    def >>= [D](cCD: C => FConsumer[A, B, D])(implicit ec: ExecutionContext): FConsumer[A, B, D] = FConsumer {
        stream => {this.apply(stream) >>= (C => cCD(C).apply(stream))}
    }

}

object FConsumer
{
    def apply[A, B, C](fn: FStream[A, B] => Future[C]): FConsumer[A, B, C] = new FConsumer[A, B, C]{
        override def apply(stream: FStream[A, B]): Future[C] = fn(stream)
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
                subscribers.foreach(_.push(line))
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
                _ <- stream.write("Enter some number:")
                a <- stream.read()
                _ <- stream.write("Enter some number once again:")
                b <- stream.read()
            } yield a + b
        }
        val result: Future[Int] = stream.transform(toInt).filter(even) <=> consumer
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
                subscribers.foreach(_.push(line))
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