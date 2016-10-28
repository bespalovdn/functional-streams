package com.github.bespalovdn

import java.util.concurrent.ConcurrentLinkedQueue

import com.github.bespalovdn.fs.impl.ClosableStreamImpl

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

package object fs
{
    type Pipe[A, B, C, D] = Stream[A, B] => Stream[C, D]
    type Consumer[A, B, C, D, E] = Stream[A, B] => Future[Consumed[C, D, E]]
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
            f.onComplete{case _ => downStream.close()}
            f.map(_.value)
        }

        private var readQueues = List.empty[ConcurrentLinkedQueue[Future[A]]]

        private class DownStream(upstream: Stream[A, B]) extends ClosableStreamImpl[A, B]{
            import scala.concurrent.ExecutionContext.Implicits.global
            val readQueue = new ConcurrentLinkedQueue[Future[A]]()

            readQueues :+= this.readQueue
            this.closed.onComplete{case _ => readQueues = readQueues.filter(_ ne this.readQueue)}

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

    case class Consumed[A, B, C](stream: Stream[A, B], value: C)

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
    def fail[A](cause: String): Future[A] = Future.failed(new ActionFailedException(cause))

    def consume[A, B]()(implicit s: Stream[A, B]): Consumed[A, B, Unit] = Consumed(s, ())
    def consume[A, B, C](value: C)(implicit s: Stream[A, B]): Consumed[A, B, C] = Consumed(s, value)

    def consumer[A, B, C](fn: => C): Consumer[A, B, A, B, C] = implicit stream => success(consume(fn))

    private def combination3[A, B, C, D, E, F, X, Y](implicit ec: ExecutionContext):
    Consumer[A, B, C, D, X] => (X => Consumer[C, D, E, F, Y]) => Consumer[A, B, E, F, Y] =
        cX => cXY => stream => for{
            x <- cX(stream)
            y <- cXY(x.value)(x.stream)
        } yield y

    private def combination4[A, B, C, D, E, F, X](implicit ec: ExecutionContext):
    Consumer[A, B, C, D, X] => Pipe[C, D, E, F] => Consumer[A, B, E, F, Unit] =
        c => p => sAB => for {
            Consumed(sCD, x) <- c(sAB)
            sEF <- Future.successful(p(sCD))
        } yield Consumed(sEF, ())
}
