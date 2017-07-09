package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.ext.FutureUtils._

import scala.concurrent.{ExecutionContext, Future}

trait FConsumer[R, W, A] extends (FStream[R, W] => Future[A]) {
    def >> [B](cB: => FConsumer[R, W, B])(implicit ec: ExecutionContext): FConsumer[R, W, B] = FConsumer {
        stream => this.apply(stream) >> cB.apply(stream)
    }
    def >>= [B](cAB: A => FConsumer[R, W, B])(implicit ec: ExecutionContext): FConsumer[R, W, B] = FConsumer {
        stream => this.apply(stream) >>= (x => cAB(x).apply(stream))
    }
    def map[B](fn: A => B)(implicit ec: ExecutionContext): FConsumer[R, W, B] = FConsumer{
        stream => this.apply(stream).map(fn)
    }
    def flatMap[B](fn: A => FConsumer[R, W, B])(implicit ec: ExecutionContext): FConsumer[R, W, B] = this >>= fn
}

object FConsumer
{
    def apply[R, W, A](fn: FStream[R, W] => Future[A]): FConsumer[R, W, A] = new FConsumer[R, W, A]{
        override def apply(stream: FStream[R, W]): Future[A] = fn(stream)
    }

    def empty[R, W]: FConsumer[R, W, Unit] = FConsumer{ stream => success() }
    def empty[R, W, A](a: => A)(implicit ec: ExecutionContext): FConsumer[R, W, A] = FConsumer{ stream => success(a) }
}

