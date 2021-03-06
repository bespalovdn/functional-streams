package com.github.bespalovdn.funcstream.manual

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream._
import com.github.bespalovdn.funcstream.config.ReadTimeout
import com.github.bespalovdn.funcstream.exception.ConnectionClosedException
import com.github.bespalovdn.funcstream.ext.FutureUtils._
import com.github.bespalovdn.funcstream.mono.Subscriber

import scala.collection.mutable
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Success

object FStreamForkTest
{
    class Mono extends Connection[String, String]
    {
        private implicit val executorContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
        private val subscribers = mutable.Set.empty[Subscriber[String]]

        override def subscribe(subscriber: Subscriber[String]): Unit = subscribers += subscriber
        override def unsubscribe(subscriber: Subscriber[String]): Unit = subscribers -= subscriber

        override def write(elem: String): Future[Unit] =
            if(closed.isCompleted) fail(new ConnectionClosedException)
            else Future { println(elem) }

        Future {
            var nextNumber = 1
            while(true){
                val line = nextNumber.toString
                subscribers.foreach(_.push(Success(line)))
                nextNumber += 1
                Thread.sleep(1000)
            }
        }
    }

    def apply(): Unit ={
        import scala.concurrent.ExecutionContext.Implicits.global

        implicit val readTimeout: ReadTimeout = ReadTimeout(1.second)
        val stream: FStream[String, String] = FStream(new Mono)

        def consumer(name: String, nTimes: Int): FConsumer[String, String, Unit] = FConsumer{
            stream => {
                if(nTimes > 0) {
                    stream.read() >>= { str =>
                        stream.write(s"$name: $str")
                        consumer(name, nTimes - 1).consume(stream)
                    }
                } else {
                    success()
                }
            }
        }

        println("Producer's output:")
        var result: Future[Unit] = {
            stream.fork() <=> (consumer("B", 3) >> consumer("C", 3))
            stream.fork() <=> consumer("A", 10)
        }
        Await.ready(result, Duration.Inf)
        Thread.sleep(3000)
        result = {
            stream.fork() <=> (consumer("B", 3) >> consumer("C", 3))
            stream.fork() <=> consumer("A", 10)
        }
        Await.ready(result, Duration.Inf)

        println("DONE")
    }
}
