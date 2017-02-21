package com.github.bespalovdn.funcstream.manual

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream._
import com.github.bespalovdn.funcstream.mono.Subscriber

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.StdIn
import scala.util.Success

object FStreamStdInTest
{
    class StdEndpoint extends Connection[String, String]{
        private val executorContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))
        private val subscribers = mutable.Set.empty[Subscriber[String]]

        override def subscribe(subscriber: Subscriber[String]): Unit = subscribers += subscriber
        override def unsubscribe(subscriber: Subscriber[String]): Unit = subscribers -= subscriber

        override def write(elem: String): Future[Unit] = Future {
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
        val result: Future[Int] = stream.transform(toInt, identity[String]).filter(even).consume(consumer)
        println("SUM OF EVENS IS: " + Await.result(result, Duration.Inf))
    }
}
