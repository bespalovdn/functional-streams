package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.ext.FutureUtils._

import scala.concurrent.{ExecutionContext, Future}

final class FConsumer[-R, +W, A](fn: FStream[R, W] => Future[A])
{
    def consume[R1 <: R, W0 >: W](stream: FStream[R1, W0]): Future[A] = fn(stream)
}

object FConsumer
{
    def empty[R, W]: FConsumer[R, W, Unit] = FConsumer{ stream => success() }
    def empty[R, W, A](a: => A)(implicit ec: ExecutionContext): FConsumer[R, W, A] = FConsumer{ stream => success(a) }

    def apply[R, W, A](fn: FStream[R, W] => Future[A]): FConsumer[R, W, A] = new FConsumer[R, W, A](stream => fn(stream))

    implicit class FConsumerOps[R, W, A](consumer: FConsumer[R, W, A])
    {
        def >> [B](cB: => FConsumer[R, W, B])(implicit ec: ExecutionContext): FConsumer[R, W, B] = FConsumer {
            stream => consumer.consume(stream) >> cB.consume(stream)
        }

        def >>= [B](cAB: A => FConsumer[R, W, B])(implicit ec: ExecutionContext): FConsumer[R, W, B] = FConsumer {
            stream => consumer.consume(stream) >>= (x => cAB(x).consume(stream))
        }

        def map[B](fn: A => B)(implicit ec: ExecutionContext): FConsumer[R, W, B] = FConsumer{
            stream => consumer.consume(stream).map(fn)
        }

        def flatMap[B](fn: A => FConsumer[R, W, B])(implicit ec: ExecutionContext): FConsumer[R, W, B] = this >>= fn
    }
}
