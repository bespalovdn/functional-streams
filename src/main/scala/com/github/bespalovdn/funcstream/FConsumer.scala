package com.github.bespalovdn.funcstream

import scala.concurrent.{ExecutionContext, Future}

trait FConsumer[A, B, C, D, X] extends (FStream[A, B] => Future[(FStream[C, D], X)])
{
    def >>= [E, F, Y](cXY: X => FConsumer[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Y] =
        FConsumer.combination1(ec)(this)(cXY)
    def >> [E, F, Y](cY: => FConsumer[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Y] =
        FConsumer.combination1(ec)(this)(_ => cY)
    def <=> [E, F](p: => Pipe[C, D, E, F])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Unit] =
        FConsumer.combination2(ec)(this)(p)
}

object FConsumer
{
    def apply[A, B, C, D, E](fn: FStream[A, B] => Future[(FStream[C, D], E)]): FConsumer[A, B, C, D, E] = {
        new FConsumer[A, B, C, D, E] {
            override def apply(stream: FStream[A, B]): Future[(FStream[C, D], E)] = fn(stream)
        }
    }

    private def combination1[A, B, C, D, E, F, X, Y](implicit ec: ExecutionContext):
        FConsumer[A, B, C, D, X] => (X => FConsumer[C, D, E, F, Y]) => FConsumer[A, B, E, F, Y] =
        cX => XcY => FConsumer { stream =>
            for {
                x <- cX.apply(stream)
                y <- XcY(x._2).apply(x._1)
            } yield y
        }

    private def combination2[A, B, C, D, E, F, X](implicit ec: ExecutionContext):
        FConsumer[A, B, C, D, X] => Pipe[C, D, E, F] => FConsumer[A, B, E, F, Unit] =
        c => p => FConsumer { sAB =>
            for {
                (sCD, x) <- c.apply(sAB)
                sEF <- success(p.apply(sCD))
            } yield (sEF, ())
        }
}