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


    //    def fork[A, B, C, D, X]: Consumer[A, B, C, D, X] => Consumer[A, B, A, B, Future[X]] = c => stream => {
    //        import scala.concurrent.ExecutionContext.Implicits.global
    //        val (s1, s2) = forkStream(stream)
    //        val fX = c(s1)
    //        fX.onComplete{
    //            case _ => s1.close()
    //        }
    //        Future.successful(Consumed(s2, fX.map(_.value)))
    //    }
    //
    //    private def forkStream[A, B]: Stream[A, B] => (ClosableStream[A, B], ClosableStream[A, B]) = stream => {
    //        import scala.concurrent.ExecutionContext.Implicits.global
    //
    //        var readQueues = List.empty[ConcurrentLinkedQueue[Future[A]]]
    //
    //        class DownStream extends ClosableStreamImpl[A, B]{
    //            val readQueue = new ConcurrentLinkedQueue[Future[A]]()
    //
    //            readQueues :+= readQueue
    //            closed.onComplete{case _ => readQueues = readQueues.filter(_ ne readQueue)}
    //
    //            override def write(elem: B): Future[Unit] = checkClosed{ stream.synchronized{ stream.write(elem) } }
    //            override def read(): Future[A] = checkClosed{
    //                readQueue.poll() match {
    //                    case null =>
    //                        val f = stream.synchronized(stream.read())
    //                        readQueues.foreach(_ offer f)
    //                        Option(readQueue.poll()).getOrElse(this.read())
    //                    case elem =>
    //                        elem
    //                }
    //            }
    //        }
    //
    //        val s1 = new DownStream()
    //        val s2 = new DownStream()
    //        (s1, s2)
    //    }
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
        FConsumer[A, B, C, D, X] => FPipe[C, D, E, F] => FConsumer[A, B, E, F, Unit] =
        c => p => FConsumer { sAB =>
            for {
                (sCD, x) <- c.apply(sAB)
                sEF <- success(p.apply(sCD))
            } yield (sEF, ())
        }
}