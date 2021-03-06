package com.github.bespalovdn.funcstream.manual.mono

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream.Resource
import com.github.bespalovdn.funcstream.config.ReadTimeout
import com.github.bespalovdn.funcstream.mono.{Consumer, Producer, Publisher, Subscriber}

import scala.collection.mutable
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.StdIn
import scala.util.Success

object StdInTest
{
    class StdReader extends Publisher[String] with Resource
    {
        private val subscribers = mutable.Set.empty[Subscriber[String]]

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
        implicit val readTimeout: ReadTimeout = ReadTimeout(1.second)

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
        val result: Future[Int] = producer.transform(toInt).filter(even) ==> consumer
        println("SUM OF EVENS IS: " + Await.result(result, Duration.Inf))
    }
}
