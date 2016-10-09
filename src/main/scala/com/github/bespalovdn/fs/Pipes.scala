package com.github.bespalovdn.fs

import java.util
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.{ExecutionContext, Future}

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
    type Consumer[A, B, C] = Stream[A, B] => Future[Consumed[A, B, C]]

    implicit class StreamOps[A, B](s: Stream[A, B]){
        def >> [C, D](p: Pipe[A, B, C, D]): Stream[C, D] = combination1(s)(p)
        def >> [C](c: => Consumer[A, B, C]): Future[C] = combination2(s)(c)
    }

    implicit class ConsumerOps[A, B, C](c: Consumer[A, B, C]){
        def >>= [D](cd: C => Consumer[A, B, D])(implicit ec: ExecutionContext): Consumer[A, B, D] = combination3(ec)(c)(cd)
        def >> [D](d: => Consumer[A, B, D])(implicit ec: ExecutionContext): Consumer[A, B, D] = combination3(ec)(c)(_ => d)
    }

    def fork[A, B, C]: Consumer[A, B, C] => Consumer[A, B, Unit] = c => stream => {
        val (s1, s2) = forkStream(stream)
        c(s1)
        Future.successful(Consumed(s2, ()))
    }

    private def combination1[A, B, C, D]: Stream[A, B] => Pipe[A, B, C, D] => Stream[C, D] = s => p => p(s)

    private def combination2[A, B, C]: Stream[A, B] => Consumer[A, B, C] => Future[C] = s => c => {
        import scala.concurrent.ExecutionContext.Implicits.global
        val fC = c(s)
        fC.onComplete{case _ => s.close()}
        fC.map(_.value)
    }

    private def combination3[A, B, C, D](implicit ec: ExecutionContext):
    Consumer[A, B, C] => (C => Consumer[A, B, D]) => Consumer[A, B, D] =
        cC => cCD => stream => for{
            c <- cC(stream)
            d <- cCD(c.value)(c.stream)
        } yield d

    private def combination4[A, B, C, D, E]: Consumer[A, B, E] => Pipe[A, B, C, D] => Consumer[C, D, Unit] = ???

    private def forkStream[A, B]: Stream[A, B] => (Stream[A, B], Stream[A, B]) = stream => {
        val q1: util.Queue[Future[A]] = new ConcurrentLinkedQueue[Future[A]]()
        val q2: util.Queue[Future[A]] = new ConcurrentLinkedQueue[Future[A]]()

        def doWrite(elem: B): Future[Unit] = stream.synchronized{ stream.write(elem) }
        def doRead(q: util.Queue[Future[A]]): Future[A] = {
            q.poll() match {
                case null =>
                    val f = stream.synchronized(stream.read())
                    q1.offer(f)
                    q2.offer(f)
                    doRead(q)
                case elem =>
                    elem
            }
        }

        class SubStream(readQueue: util.Queue[Future[A]]) extends Stream[A, B]{
            override def read(): Future[A] = doRead(readQueue)
            override def write(elem: B): Future[Unit] = doWrite(elem)
            override def close(): Future[Unit] = ???
        }

        ???
    }
}
