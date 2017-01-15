package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.ext.FutureExtensions._

import scala.concurrent.{ExecutionContext, Future}

trait FConsumer[A, B, X] extends (FStream[A, B] => Future[X]) {
    def >> [Y](cD: => FConsumer[A, B, Y])(implicit ec: ExecutionContext): FConsumer[A, B, Y] = FConsumer {
        stream => {this.apply(stream) >> cD.apply(stream)}
    }
    def >>= [Y](cCD: X => FConsumer[A, B, Y])(implicit ec: ExecutionContext): FConsumer[A, B, Y] = FConsumer {
        stream => {this.apply(stream) >>= (C => cCD(C).apply(stream))}
    }
    def flatMap[Y](fn: X => FConsumer[A, B, Y])(implicit ec: ExecutionContext): FConsumer[A, B, Y] = this >>= fn
}

object FConsumer
{
    def apply[A, B, X](fn: FStream[A, B] => Future[X]): FConsumer[A, B, X] = new FConsumer[A, B, X]{
        override def apply(stream: FStream[A, B]): Future[X] = fn(stream)
    }
}

