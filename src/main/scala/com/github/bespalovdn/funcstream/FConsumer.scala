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

//trait FConsumer[-R, +W, A] {
//    def apply[R1 <: R, W1 >: W](stream: FStream[R1, W1]): Future[A]
//
//    def >> [R1 <: R, W1 >: W, B](cB: => FConsumer[R1, W1, B])(implicit ec: ExecutionContext): FConsumer[R1, W1, B] = FConsumer {
//        stream: FStream[R, W] => this.apply(stream) >> cB.apply(stream)
//    }
//    def >>= [R1 <: R, W1 >: W, B](cAB: A => FConsumer[R1, W1, B])(implicit ec: ExecutionContext): FConsumer[R1, W1, B] = FConsumer {
//        stream: FStream[R, W] => this.apply(stream) >>= (x => cAB(x).apply(stream))
//    }
//    def map[B](fn: A => B)(implicit ec: ExecutionContext): FConsumer[R, W, B] = FConsumer{
//        stream: FStream[R, W] => this.apply(stream).map(fn)
//    }
//    def flatMap[R1 <: R, W1 >: W, B](fn: A => FConsumer[R1, W1, B])(implicit ec: ExecutionContext): FConsumer[R1, W1, B] = this >>= fn
//}
//
//object FConsumer
//{
//    def apply[R, W, A](fn: FStream[R, W] => Future[A]): FConsumer[R, W, A] = new FConsumer[R, W, A]{
//        override def apply[R1 <: R, W1 >: W](stream: FStream[R1, W1]): Future[A] = fn(stream)
//    }
//
//    def empty[R, W]: FConsumer[R, W, Unit] = FConsumer[R, W, Unit]{ stream => success() }
//    def empty[R, W, A](a: => A)(implicit ec: ExecutionContext): FConsumer[R, W, A] = FConsumer[R, W, A]{ stream => success(a) }
//}

