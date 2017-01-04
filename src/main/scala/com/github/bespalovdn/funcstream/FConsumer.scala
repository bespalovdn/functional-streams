package com.github.bespalovdn.funcstream

import java.util.concurrent.ConcurrentLinkedQueue

import com.github.bespalovdn.funcstream.impl.{ClosableStream, ClosableStreamImpl}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
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

    def fork[A, B, C, D, Y](implicit ec: ExecutionContext):
    FConsumer[A, B, C, D, Y] => FConsumer[A, B, A, B, Future[Y]] = cY => FConsumer { stream => {
            val (s1, s2) = FConsumer.forkStream(stream)
            val fY = cY.apply(s2)
            fY.onComplete { _ => s2.close() }
            success(consume(fY.map(_._2))(s1))
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

    private def forkStream[A, B]: FStream[A, B] => (ClosableStream[A, B], ClosableStream[A, B]) = stream => {
        import scala.concurrent.ExecutionContext.Implicits.global

        val readQueues = ListBuffer.empty[ConcurrentLinkedQueue[Future[A]]]

        class DownStream extends ClosableStreamImpl[A, B]{
            val readQueue = new ConcurrentLinkedQueue[Future[A]]()

            readQueues.synchronized{ readQueues += readQueue }
            closed.onComplete{case _ => readQueues.synchronized{ readQueues -= readQueue }}

            override def write(elem: B): Future[Unit] = checkClosed{ stream.synchronized{ stream.write(elem) } }
            override def read(timeout: Duration): Future[A] = checkClosed{
                readQueue.poll() match {
                    case null =>
                        val f = stream.synchronized(stream.read(timeout))
                        readQueues.foreach(_ offer f)
                        Option(readQueue.poll()).get //must be initialized
                    case elem =>
                        elem
                }
            }
        }

        val s1 = new DownStream()
        val s2 = new DownStream()
        (s1, s2)
    }
}