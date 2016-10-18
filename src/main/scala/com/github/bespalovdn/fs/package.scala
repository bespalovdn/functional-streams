package com.github.bespalovdn

import java.util.concurrent.ConcurrentLinkedQueue

import com.github.bespalovdn.fs.impl.{ClosableStream, ClosableStreamImpl}

import scala.concurrent.{ExecutionContext, Future}

package object fs
{
    type Pipe[A, B, C, D] = Stream[A, B] => Stream[C, D]
    type Consumer[A, B, C, D, E] = Stream[A, B] => Future[Consumed[C, D, E]]

    trait Stream[A, B]
    {
        def read(): Future[A]
        def write(elem: B): Future[Unit]
    }

    case class Consumed[A, B, C](stream: Stream[A, B], value: C)

    implicit class StreamOps[A, B](s: Stream[A, B]){
        def <|> [C, D](p: Pipe[A, B, C, D]): Stream[C, D] = combination1(s)(p)
        def <*> [C, D, X](c: => Consumer[A, B, C, D, X]): Future[X] = combination2(s)(c)
    }

    implicit class ConsumerOps[A, B, C, D, X](cX: Consumer[A, B, C, D, X]){
        def >>= [E, F, Y](cXY: X => Consumer[C, D, E, F, Y])(implicit ec: ExecutionContext): Consumer[A, B, E, F, Y] =
            combination3(ec)(cX)(cXY)
        def >> [E, F, Y](cY: => Consumer[C, D, E, F, Y])(implicit ec: ExecutionContext): Consumer[A, B, E, F, Y] =
            combination3(ec)(cX)(_ => cY)
        def <*> [E, F](p: => Pipe[C, D, E, F])(implicit ec: ExecutionContext): Consumer[A, B, E, F, Unit] =
            combination4(ec)(cX)(p)
    }

    def fork[A, B, C, D, X]: Consumer[A, B, C, D, X] => Consumer[A, B, A, B, Future[X]] = c => stream => {
        import scala.concurrent.ExecutionContext.Implicits.global
        val (s1, s2) = forkStream(stream)
        val fX = c(s1)
        fX.onComplete{
            case _ => s1.close()
        }
        Future.successful(Consumed(s2, fX.map(_.value)))
    }

    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())
    def fail[A](cause: String): Future[A] = Future.failed(new ActionFailedException(cause))

    def consume[A, B]()(implicit s: Stream[A, B]): Consumed[A, B, Unit] = Consumed(s, ())
    def consume[A, B, C](value: C)(implicit s: Stream[A, B]): Consumed[A, B, C] = Consumed(s, value)

    def consumer[A, B, C](fn: => C): Consumer[A, B, A, B, C] = implicit stream => success(consume(fn))

    private def combination1[A, B, C, D]: Stream[A, B] => Pipe[A, B, C, D] => Stream[C, D] = s => p => p(s)

    private def combination2[A, B, C, D, E]: Stream[A, B] => Consumer[A, B, C, D, E] => Future[E] = s => c => {
        import scala.concurrent.ExecutionContext.Implicits.global
        c(s).map(_.value)
    }

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

    private def forkStream[A, B]: Stream[A, B] => (ClosableStream[A, B], ClosableStream[A, B]) = stream => {
        import scala.concurrent.ExecutionContext.Implicits.global

        var readQueues = List.empty[ConcurrentLinkedQueue[Future[A]]]

        class DownStream extends ClosableStreamImpl[A, B]{
            val readQueue = new ConcurrentLinkedQueue[Future[A]]()

            readQueues :+= readQueue
            closed.onComplete{case _ => readQueues = readQueues.filter(_ ne readQueue)}

            override def write(elem: B): Future[Unit] = checkClosed{ stream.synchronized{ stream.write(elem) } }
            override def read(): Future[A] = checkClosed{
                readQueue.poll() match {
                    case null =>
                        val f = stream.synchronized(stream.read())
                        readQueues.foreach(_ offer f)
                        Option(readQueue.poll()).getOrElse(this.read())
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
