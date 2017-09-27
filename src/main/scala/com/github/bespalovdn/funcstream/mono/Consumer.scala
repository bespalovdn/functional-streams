package com.github.bespalovdn.funcstream.mono

import com.github.bespalovdn.funcstream.ext.FutureUtils._

import scala.concurrent.{ExecutionContext, Future}

trait Consumer[-A, +B] {
    def consume(p: Producer[A]): Future[B]
    def >> [A1 <: A, C](cC: => Consumer[A1, C])(implicit ec: ExecutionContext): Consumer[A1, C] = Consumer {
        p => {this.consume(p) >> cC.consume(p)}
    }
}

object Consumer
{
    def apply[A, B](fn: Producer[A] => Future[B]): Consumer[A, B] = new Consumer[A, B] {
        override def consume(p: Producer[A]): Future[B] = fn(p)
    }
}