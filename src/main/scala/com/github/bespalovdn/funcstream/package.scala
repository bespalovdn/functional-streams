package com.github.bespalovdn

import java.util.concurrent.ConcurrentLinkedQueue

import com.github.bespalovdn.funcstream.impl.ClosableStreamImpl

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

package object funcstream
{
    type Consumer[A, B, C, D, E] = Stream[A, B] => Future[(Stream[C, D], E)]
    type ConstConsumer[A, B, C] = Consumer[A, B, A, B, C]

    trait Stream[A, B]
    {
        def read(timeout: Duration = null): Future[A]
        def write(elem: B): Future[Unit]

        def <*> [C, D](p: Pipe[A, B, C, D]): Stream[C, D] = {
            p(this)
        }

        def <=> [C, D, X](c: => Consumer[A, B, C, D, X]): Future[X] = {
            import scala.concurrent.ExecutionContext.Implicits.global
            val downStream = new DownStream(this)
            val f = c(downStream)
            f.onComplete{_ => downStream.close()}
            f.map(_._2)
        }

        private lazy val readQueues = ListBuffer.empty[ConcurrentLinkedQueue[Future[A]]]

        private class DownStream(upstream: Stream[A, B]) extends ClosableStreamImpl[A, B]{
            import scala.concurrent.ExecutionContext.Implicits.global
            val readQueue = new ConcurrentLinkedQueue[Future[A]]()

            readQueues.synchronized{ readQueues += this.readQueue }
            this.closed.onComplete{_ => readQueues.synchronized{ readQueues -= this.readQueue }}

            override def write(elem: B): Future[Unit] = checkClosed{ upstream.synchronized{ upstream.write(elem) } }
            override def read(timeout: Duration): Future[A] = checkClosed{
                this.readQueue.poll() match {
                    case null =>
                        val f = upstream.synchronized(upstream.read(timeout))
                        readQueues.foreach(_ offer f)
                        Option(this.readQueue.poll()).getOrElse(this.read())
                    case elem =>
                        elem
                }
            }
        }
    }

    implicit class ConsumerOps[A, B, C, D, X](cX: Consumer[A, B, C, D, X]){
        def >>= [E, F, Y](cXY: X => Consumer[C, D, E, F, Y])(implicit ec: ExecutionContext): Consumer[A, B, E, F, Y] =
            combination3(ec)(cX)(cXY)
        def >> [E, F, Y](cY: => Consumer[C, D, E, F, Y])(implicit ec: ExecutionContext): Consumer[A, B, E, F, Y] =
            combination3(ec)(cX)(_ => cY)
        def <=> [E, F](p: => Pipe[C, D, E, F])(implicit ec: ExecutionContext): Consumer[A, B, E, F, Unit] =
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

    def consume[A, B]()(implicit s: Stream[A, B]): (Stream[A, B], Unit) = (s, ())
    def consume[A, B, C](value: C)(implicit s: Stream[A, B]): (Stream[A, B], C) = (s, value)

    def consumer[A, B, C](fn: => C): Consumer[A, B, A, B, C] = implicit stream => success(consume(fn))

    private def combination3[A, B, C, D, E, F, X, Y](implicit ec: ExecutionContext):
    Consumer[A, B, C, D, X] => (X => Consumer[C, D, E, F, Y]) => Consumer[A, B, E, F, Y] =
        cX => cXY => stream => for{
            x <- cX(stream)
            y <- cXY(x._2)(x._1)
        } yield y

    private def combination4[A, B, C, D, E, F, X](implicit ec: ExecutionContext):
    Consumer[A, B, C, D, X] => Pipe[C, D, E, F] => Consumer[A, B, E, F, Unit] =
        c => p => sAB => for {
            (sCD, x) <- c(sAB)
            sEF <- success(p.apply(sCD))
        } yield (sEF, ())
}