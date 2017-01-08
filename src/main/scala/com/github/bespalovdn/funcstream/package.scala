package com.github.bespalovdn

import scala.concurrent.Future

package object funcstream
{
    type FPlainConsumer[A, B, C] = FConsumer[A, B, A, B, C]

    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())

    def consume[A, B]()(implicit s: FStream[A, B]): (FStream[A, B], Unit) = (s, ())
    def consume[A, B, C](value: C)(implicit s: FStream[A, B]): (FStream[A, B], C) = (s, value)

    def consumer[A, B, C](fn: => C): FConsumer[A, B, A, B, C] = FConsumer{implicit stream => success(consume(fn))}
}
