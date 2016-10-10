package com.github.bespalovdn.fs

import java.util.concurrent.ConcurrentLinkedQueue

import com.github.bespalovdn.fs.FutureUtils._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait Stream[A, B]
{
    def read(): Future[A]
    def write(elem: B): Future[Unit]
    def close(): Future[Unit]
}

case class Consumed[A, B, C](stream: Stream[A, B], value: C)

object Pipes
{
    type Pipe[A, B, C, D] = Stream[A, B] => Stream[C, D]
    type Consumer[A, B, C, D, E] = Stream[A, B] => Future[Consumed[C, D, E]]

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

    def fork[A, B, C, D, X]: Consumer[A, B, C, D, X] => Consumer[A, B, A, B, Unit] = c => stream => {
        val (s1, s2) = forkStream(stream)
        c(s1).onComplete{
            case Success(Consumed(stream1, _)) => stream1.close()
            case Failure(_) => s1.close()
        }
        Future.successful(Consumed(s2, ()))
    }

    private def combination1[A, B, C, D]: Stream[A, B] => Pipe[A, B, C, D] => Stream[C, D] = s => p => p(s)

    private def combination2[A, B, C, D, E]: Stream[A, B] => Consumer[A, B, C, D, E] => Future[E] = s => c => {
        import scala.concurrent.ExecutionContext.Implicits.global
        val fC = c(s)
        fC.onComplete{
            case Success(Consumed(s2, _)) => s2.close()
            case Failure(_) => s.close()
        }
        fC.map(_.value)
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

    private def forkStream[A, B]: Stream[A, B] => (Stream[A, B], Stream[A, B]) = stream => {
        trait Queue{
            def isClosed: Boolean
            def offer(elem: Future[A]): Unit = if(!isClosed) q.offer(elem)
            def poll(): Future[A] = q.poll()

            private val q = new ConcurrentLinkedQueue[Future[A]]()
        }

        var downStreams = List.empty[DownStream]

        class DownStream extends ClosableStream[A, B]{
            downStreams :+= this
            val readQueue: Queue = new Queue {
                override def isClosed: Boolean = closed.value.isDefined
            }
            override def write(elem: B): Future[Unit] = checkClosed{ stream.synchronized{ stream.write(elem) } }
            override def read(): Future[A] = checkClosed{
                readQueue.poll() match {
                    case null =>
                        val f = stream.synchronized(stream.read())
                        downStreams.foreach(_.readQueue.offer(f))
                        Option(readQueue.poll()).getOrElse(Future.failed(new StreamClosedException))
                    case elem =>
                        elem
                }
            }
        }

        // Close upstream when both downstreams are closed:
        def closeUpstream(s1: ClosableStream[A, B], s2: ClosableStream[A, B]): Unit = {
            import scala.concurrent.ExecutionContext.Implicits.global
            s1.closed >> s2.closed >> {stream.close()}
        }

        val s1 = new DownStream()
        val s2 = new DownStream()
        closeUpstream(s1, s2)
        (s1, s2)
    }
}
