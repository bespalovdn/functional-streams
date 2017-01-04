package com.github.bespalovdn

import scala.concurrent.Future

package object funcstream
{
    type FPlainConsumer[A, B, C] = FConsumer[A, B, A, B, C]

//    def fork[A, B, C, D, X]: Consumer[A, B, C, D, X] => Consumer[A, B, A, B, Future[X]] = c => stream => {
//        val fX = stream <=> c
//        Future.successful(Consumed(stream, fX))
//    }

    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())
    //TODO: remove ActionFailedException. make this function receiving Throwable.
    def fail[A](cause: String): Future[A] = Future.failed(new ActionFailedException(cause))

    def consume[A, B]()(implicit s: FStream[A, B]): (FStream[A, B], Unit) = (s, ())
    def consume[A, B, C](value: C)(implicit s: FStream[A, B]): (FStream[A, B], C) = (s, value)

    def consumer[A, B, C](fn: => C): FConsumer[A, B, A, B, C] = FConsumer{implicit stream => success(consume(fn))}
}
