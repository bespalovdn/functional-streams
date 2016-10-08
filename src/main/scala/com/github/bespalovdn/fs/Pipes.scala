package com.github.bespalovdn.fs

import scala.concurrent.{ExecutionContext, Future}

trait Stream[A, B]
{
    def read(): Future[A]
    def write(elem: B): Future[Unit]
    def close(): Future[Unit]
}

object Pipes
{
    type Pipe[A, B, C, D] = Stream[A, B] => Stream[C, D]
    type Consumer[A, B, C] = Stream[A, B] => Future[C]

    def combination1[A, B, C, D]: Stream[A, B] => Pipe[A, B, C, D] => Stream[C, D] = s => p => p(s)
    def combination2[A, B, C]: Stream[A, B] => Consumer[A, B, C] => Future[C] = s => cC => {
        import scala.concurrent.ExecutionContext.Implicits.global
        val fC = cC(s)
        fC.onComplete{case _ => s.close()}
        fC
    }
    def combination3[A, B, C, D](implicit ec: ExecutionContext):
    Consumer[A, B, C] => (C => Consumer[A, B, D]) => Consumer[A, B, D] = cC => cCD => stream => for{
        c <- cC(stream)
        d <- cCD(c)(stream)
    } yield d


    implicit class StreamOps[A, B](s: Stream[A, B]){
        def >> [C, D](p: Pipe[A, B, C, D]): Stream[C, D] = combination1(s)(p)
        def >> [C](c: => Consumer[A, B, C]): Future[C] = combination2(s)(c)
    }

    implicit class ConsumerOps[A, B, C](c: Consumer[A, B, C]){
        def >>= [D](cd: C => Consumer[A, B, D])(implicit ec: ExecutionContext): Consumer[A, B, D] = combination3(ec)(c)(cd)
        def >> [D](d: => Consumer[A, B, D])(implicit ec: ExecutionContext): Consumer[A, B, D] = combination3(ec)(c)(_ => d)
    }
}
