package com.github.bespalovdn.funcstream.v2

import com.github.bespalovdn.funcstream.ext.FutureExtensions._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

trait Publisher[A]{
    def subscribe(subscriber: Subscriber[A])
    def unsubscribe(subscriber: Subscriber[A])
}

trait Subscriber[A]{
    def push(elem: A)
}

////////////////////////////////////////////////////////////////
trait Producer[A]{
    def get(timeout: Duration = null): Future[A]

    def <=> [B](c: Consumer[A, B]): Future[B] = c.apply(this)
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

}