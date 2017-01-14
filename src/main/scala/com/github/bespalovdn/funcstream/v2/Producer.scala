package com.github.bespalovdn.funcstream.v2

import java.util.concurrent.Executors

import com.github.bespalovdn.funcstream.ext.FutureExtensions._

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
class Producer[A](publisher: Publisher[A]) extends Subscriber[A]
{
    val available = mutable.Queue.empty[A]
    val requested = mutable.Queue.empty[Promise[A]]

    override def push(elem: A): Unit = {
        if(requested.nonEmpty)
            requested.dequeue().trySuccess(elem)
        else
            available.enqueue(elem)
    }

    def get(timeout: Duration = null): Future[A] = {
        if(available.nonEmpty) {
            Future.successful(available.dequeue())
        } else{
            val p = Promise[A]
            requested.enqueue(p)
            p.future
        }
    }

    def <=> [B](c: Consumer[A, B])(implicit ec: ExecutionContext): Future[B] = {
        publisher.subscribe(this)
        val f = c.apply(this)
        f.onComplete(_ => publisher.unsubscribe(this))
        f
    }
    def <=> [B](t: Transformer[A, B]): Producer[B] = t.apply(this)
}

trait Consumer[A, B] extends (Producer[A] => Future[B]) {
    def >> [C](cC: => Consumer[A, C])(implicit ec: ExecutionContext): Consumer[A, C] = Consumer {
        p => {this.apply(p) >> cC.apply(p)}
    }
}

trait Transformer[A, B] extends (Producer[A] => Producer[B])

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
        val subscribers = mutable.ListBuffer.empty[Subscriber[String]]

        override def subscribe(subscriber: Subscriber[String]): Unit = subscribers += subscriber
        override def unsubscribe(subscriber: Subscriber[String]): Unit = subscribers -= subscriber

        val executorContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

        Future {
            while(true){
                val line = StdIn.readLine()
                subscribers.foreach(_.push(line))
            }
        }(executorContext)
    }

    def apply(): Unit = {
        val producer: Producer[String] = new Producer(new stdReader)
        val consumer: Consumer[String, Int] = Consumer{
            p => for {
                a <- p.get().map(_.toInt)
                b <- p.get().map(_.toInt)
            } yield a + b
        }

        println("Input some numbers:")
        val result: Future[Int] = producer <=> consumer
        println("SUM IS: " + Await.result(result, Duration.Inf))
    }
}