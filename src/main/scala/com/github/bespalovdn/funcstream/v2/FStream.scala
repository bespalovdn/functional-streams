package com.github.bespalovdn.funcstream.v2

import com.github.bespalovdn.funcstream.ext.FutureExtensions._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

trait EndPoint[A, B] extends Publisher[A] with Subscriber[B]

trait FStream[A, B]{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B, timeout: Duration = null): Future[Unit]
    def <=> [C](c: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C]
}

trait FConsumer[A, B, C] extends (FStream[A, B] => Future[C]) {
    def >> [D](cD: => FConsumer[A, B, D])(implicit ec: ExecutionContext): FConsumer[A, B, D] = FConsumer {
        stream => {this.apply(stream) >> cD.apply(stream)}
    }
}

object FConsumer
{
    def apply[A, B, C](fn: FStream[A, B] => Future[C]): FConsumer[A, B, C] = new FConsumer[A, B, C]{
        override def apply(stream: FStream[A, B]): Future[C] = fn(stream)
    }
}