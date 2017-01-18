package com.github.bespalovdn.funcstream.manual

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream._
import com.github.bespalovdn.funcstream.ext.FutureExtensions
import com.github.bespalovdn.funcstream.mono.Subscriber

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Success

object FStreamForkTest extends FutureExtensions
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
                    success()
                }
            }
        }

        println("Producer's output:")
        var result: Future[Unit] = {
            stream.fork().consume(consumer("B", 3) >> consumer("C", 3))
            stream.fork().consume(consumer("A", 10))
        }
        Await.ready(result, Duration.Inf)
        Thread.sleep(3000)
        result = {
            stream.fork().consume(consumer("B", 3) >> consumer("C", 3))
            stream.fork().consume(consumer("A", 10))
        }
        Await.ready(result, Duration.Inf)

        println("DONE")
    }
}
