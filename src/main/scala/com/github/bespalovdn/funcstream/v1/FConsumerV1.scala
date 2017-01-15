package com.github.bespalovdn.funcstream.v1

import scala.concurrent.{ExecutionContext, Future}

trait FConsumerV1[A, B, C, D, X] extends (FStreamV1[A, B] => Future[(FStreamV1[C, D], X)])
{
    def >>= [E, F, Y](cXY: X => FConsumerV1[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumerV1[A, B, E, F, Y] =
        FConsumerV1.combination1(ec)(this)(cXY)
    def >> [E, F, Y](cY: => FConsumerV1[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumerV1[A, B, E, F, Y] =
        FConsumerV1.combination1(ec)(this)(_ => cY)
    def <=> [E, F](p: => FPipe[C, D, E, F])(implicit ec: ExecutionContext): FConsumerV1[A, B, E, F, Unit] =
        FConsumerV1.combination2(ec)(this)(p)

    def map[Y](fn: X => Y)(implicit ec: ExecutionContext): FConsumerV1[A, B, C, D, Y] = FConsumerV1 { sAB => for {
            (sCD, x) <- this.apply(sAB)
        } yield consume(fn(x))(sCD)
    }

    def flatMap[E, F, Y](fn: X => FConsumerV1[C, D, E, F, Y])(implicit ec: ExecutionContext): FConsumerV1[A, B, E, F, Y] =
    FConsumerV1 { (sAB: FStreamV1[A, B]) => {
            import com.github.bespalovdn.funcstream.ext.FutureExtensions._
            this.apply(sAB) >>= {
                case (sCD, x) =>
                    val cY: FConsumerV1[C, D, E, F, Y] = fn(x)
                    cY.apply(sCD)
            }
        }
    }
}

object FConsumerV1
{
    def apply[A, B, C, D, E](fn: FStreamV1[A, B] => Future[(FStreamV1[C, D], E)]): FConsumerV1[A, B, C, D, E] = {
        new FConsumerV1[A, B, C, D, E] {
            override def apply(stream: FStreamV1[A, B]): Future[(FStreamV1[C, D], E)] = fn(stream)
        }
    }

    def fork[A, B, C, D, Y](cY: FConsumerV1[A, B, C, D, Y])
                           (implicit ec: ExecutionContext): FConsumerV1[A, B, A, B, Future[Y]] = FConsumerV1 { implicit stream => {
            val fY = stream.asInstanceOf[FStreamConnector[A, B]] <=> cY
            success(consume(fY))
        }
    }

    private def combination1[A, B, C, D, E, F, X, Y](implicit ec: ExecutionContext):
        FConsumerV1[A, B, C, D, X] => (X => FConsumerV1[C, D, E, F, Y]) => FConsumerV1[A, B, E, F, Y] =
        cX => XcY => FConsumerV1 { stream =>
            for {
                x <- cX.apply(stream)
                y <- XcY(x._2).apply(x._1)
            } yield y
        }

    private def combination2[A, B, C, D, E, F, X](implicit ec: ExecutionContext):
        FConsumerV1[A, B, C, D, X] => FPipe[C, D, E, F] => FConsumerV1[A, B, E, F, Unit] =
        c => p => FConsumerV1 { sAB =>
            for {
                (sCD, x) <- c.apply(sAB)
                sEF <- success(p.apply(sCD))
            } yield (sEF, ())
        }
}