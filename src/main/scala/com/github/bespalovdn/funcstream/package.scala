package com.github.bespalovdn

import scala.concurrent.{ExecutionContext, Future}

package object funcstream
{
    type FConsumer[A, B, C, D, E] = FStream[A, B] => Future[(FStream[C, D], E)]
    type ConstConsumer[A, B, C] = FConsumer[A, B, A, B, C]

    implicit class ConsumerOps[A, B, C, D, X](cX: FConsumer[A, B, C, D, X]){
        def >>= [E, F, Y](cXY: X => FConsumer[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Y] =
            combination3(ec)(cX)(cXY)
        def >> [E, F, Y](cY: => FConsumer[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Y] =
            combination3(ec)(cX)(_ => cY)
        def <=> [E, F](p: => Pipe[C, D, E, F])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Unit] =
            combination4(ec)(cX)(p)
    }

//    def fork[A, B, C, D, X]: Consumer[A, B, C, D, X] => Consumer[A, B, A, B, Future[X]] = c => stream => {
//        val fX = stream <=> c
//        Future.successful(Consumed(stream, fX))
//    }

    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())
    //TODO: remove ActionFailedException. make this function receiving Throwable.
    def fail[A](cause: String): Future[A] = Future.failed(new ActionFailedException(cause))

    def consume[A, B]()(implicit s: FStream[A, B]): (FStream[A, B], Unit) = (s, ())
    def consume[A, B, C](value: C)(implicit s: FStream[A, B]): (FStream[A, B], C) = (s, value)

    def consumer[A, B, C](fn: => C): FConsumer[A, B, A, B, C] = implicit stream => success(consume(fn))

    private def combination3[A, B, C, D, E, F, X, Y](implicit ec: ExecutionContext):
    FConsumer[A, B, C, D, X] => (X => FConsumer[C, D, E, F, Y]) => FConsumer[A, B, E, F, Y] =
        cX => cXY => stream => for{
            x <- cX(stream)
            y <- cXY(x._2)(x._1)
        } yield y

    private def combination4[A, B, C, D, E, F, X](implicit ec: ExecutionContext):
    FConsumer[A, B, C, D, X] => Pipe[C, D, E, F] => FConsumer[A, B, E, F, Unit] =
        c => p => sAB => for {
            (sCD, x) <- c(sAB)
            sEF <- success(p.apply(sCD))
        } yield (sEF, ())
}
