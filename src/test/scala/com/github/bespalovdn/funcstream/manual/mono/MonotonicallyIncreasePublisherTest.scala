package com.github.bespalovdn.funcstream.manual.mono

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream.ext.FutureExtensions
import com.github.bespalovdn.funcstream.mono.{Consumer, Producer, Publisher, Subscriber}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Success

object MonotonicallyIncreasePublisherTest extends FutureExtensions
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
        var result: Future[Unit] = producer.fork(p => p.consume(consumer("B", 3) >> consumer("C", 3))) consume consumer("A", 10)
        Await.ready(result, Duration.Inf)
        Thread.sleep(3000)
        result = producer.fork(p => p.consume(consumer("B", 3) >> consumer("C", 3))) consume consumer("A", 10)
        Await.ready(result, Duration.Inf)

        println("DONE")
    }
}
