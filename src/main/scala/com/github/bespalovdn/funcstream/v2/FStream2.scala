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

trait FStream2[A, B]{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]
    def <=> [C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C]
    def fork(consumer: FStream2[A, B] => Unit): FStream2[A, B]
}

object FStream2
{
    def apply[A, B](endPoint: EndPoint[A, B]): FStream2[A, B] = new FStreamImpl[A, B](endPoint)

    private class FStreamImpl[A, B](endPoint: EndPoint[A, B])
        extends FStream2[A, B]
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

        override def fork(consumer: FStream2[A, B] => Unit): FStream2[A, B] = {
            def getPublisher(producer: Producer[A]): Publisher[A] = producer.asInstanceOf[ProducerImpl[A]].publisher
            def toStream(producer: Producer[A]): FStream2[A, B] = new FStreamImpl[A, B](new ProxyEndPoint(getPublisher(producer)))
            toStream(reader.fork(p => consumer(toStream(p))))
        }

        private class ProxyEndPoint(publisher: Publisher[A]) extends EndPoint[A, B] with Subscriber[A] {
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
            override def write(elem: B): Unit = endPoint.write(elem)
            override def push(elem: A): Unit = subscribers.foreach(_.push(elem))
        }

    }
}

trait FConsumer[A, B, C] extends (FStream2[A, B] => Future[C]) {
    def >> [D](cD: => FConsumer[A, B, D])(implicit ec: ExecutionContext): FConsumer[A, B, D] = FConsumer {
        stream => {this.apply(stream) >> cD.apply(stream)}
    }
}

object FConsumer
{
    def apply[A, B, C](fn: FStream2[A, B] => Future[C]): FConsumer[A, B, C] = new FConsumer[A, B, C]{
        override def apply(stream: FStream2[A, B]): Future[C] = fn(stream)
    }
}

////////////////////////////////////////////////////////////////
object FStreamStdInTest
{
    class StdEndpoint extends EndPoint[String, String]{
        private val executorContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))
        private val subscribers = mutable.ListBuffer.empty[Subscriber[String]]

        override def subscribe(subscriber: Subscriber[String]): Unit = {
            subscribers += subscriber
        }
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

        val stream: FStream2[String, String] = FStream2(new StdEndpoint)
        //        val toInt: String => Int = _.toInt // transformer
        //        val even: Int => Boolean = i => i % 2 == 0 // filter
        val consumer: FConsumer[String, String, Int] = FConsumer { stream =>
            for {
                _ <- stream.write("Enter some number:")
                a <- stream.read().map(_.toInt)
                _ <- stream.write("Enter some number once again:")
                b <- stream.read().map(_.toInt)
            } yield a + b
        }
        val result: Future[Int] = stream <=> consumer
        println("SUM OF EVENS IS: " + Await.result(result, Duration.Inf))
    }
}
