package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.ext.FutureUtils._

import scala.concurrent.{ExecutionContext, Future}

trait FConsumer[A, B, X] extends (FStream[A, B] => Future[X]) {
    def >> [Y](cY: => FConsumer[A, B, Y])(implicit ec: ExecutionContext): FConsumer[A, B, Y] = FConsumer {
        stream => this.apply(stream) >> cY.apply(stream)
    }
    def >>= [Y](cXY: X => FConsumer[A, B, Y])(implicit ec: ExecutionContext): FConsumer[A, B, Y] = FConsumer {
        stream => this.apply(stream) >>= (x => cXY(x).apply(stream))
    }
    def map[Y](fn: X => Y)(implicit ec: ExecutionContext): FConsumer[A, B, Y] = FConsumer{
        stream => this.apply(stream).map(fn)
    }
    def flatMap[Y](fn: X => FConsumer[A, B, Y])(implicit ec: ExecutionContext): FConsumer[A, B, Y] = this >>= fn
}

object FConsumer
{
    def apply[A, B, X](fn: FStream[A, B] => Future[X]): FConsumer[A, B, X] = new FConsumer[A, B, X]{
        override def apply(stream: FStream[A, B]): Future[X] = fn(stream)
    }

    def empty[A, B]: FConsumer[A, B, Unit] = FConsumer{ stream => success() }
    def empty[A, B, C](c: => C)(implicit ec: ExecutionContext): FConsumer[A, B, C] = FConsumer{ stream => success(c) }
}

