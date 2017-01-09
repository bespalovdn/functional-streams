package com.github.bespalovdn.funcstream

import scala.concurrent.{ExecutionContext, Future}

trait FConsumer[A, B, C, D, X] extends (FStream[A, B] => Future[(FStream[C, D], X)])
{
    def >>= [E, F, Y](cXY: X => FConsumer[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Y] =
        FConsumer.combination1(ec)(this)(cXY)
    def >> [E, F, Y](cY: => FConsumer[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Y] =
        FConsumer.combination1(ec)(this)(_ => cY)
    def <=> [E, F](p: => FPipe[C, D, E, F])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Unit] =
        FConsumer.combination2(ec)(this)(p)

    def map[Y](fn: X => Y)(implicit ec: ExecutionContext): FConsumer[A, B, C, D, Y] = FConsumer { sAB => for {
            (sCD, x) <- this.apply(sAB)
        } yield consume(fn(x))(sCD)
    }

    def flatMap[E, F, Y](fn: X => FConsumer[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumer[A, B, E, F, Y] =
    FConsumer { (sAB: FStream[A, B]) => {
            import FutureExtensions._
            this.apply(sAB) >>= {
                case (sCD, x) =>
                    val cY: FConsumer[C, D, E, F, Y] = fn(x)
                    cY.apply(sCD)
            }
        }
    }
}

object FConsumer
{
    def apply[A, B, C, D, E](fn: FStream[A, B] => Future[(FStream[C, D], E)]): FConsumer[A, B, C, D, E] = {
        new FConsumer[A, B, C, D, E] {
            override def apply(stream: FStream[A, B]): Future[(FStream[C, D], E)] = fn(stream)
        }
    }

    def fork[A, B, C, D, Y](cY: FConsumer[A, B, C, D, Y])
                           (implicit ec: ExecutionContext): FConsumer[A, B, A, B, Future[Y]] = FConsumer { implicit stream => {
            val fY = stream <=> cY
            success(consume(fY))
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
        FConsumer[A, B, C, D, X] => FPipe[C, D, E, F] => FConsumer[A, B, E, F, Unit] =
        c => p => FConsumer { sAB =>
            for {
                (sCD, x) <- c.apply(sAB)
                sEF <- success(p.apply(sCD))
            } yield (sEF, ())
        }
}