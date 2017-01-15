package com.github.bespalovdn

import scala.concurrent.Future

package object funcstream
{
    type FPlainConsumer[A, B, C] = FConsumer[A, B, A, B, C]

    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())

    def fail[A](t: Throwable): Future[A] = Future.failed(t)

    def consume[A, B]()(implicit s: FStreamV1[A, B]): (FStreamV1[A, B], Unit) = (s, ())
    def consume[A, B, C](value: C)(implicit s: FStreamV1[A, B]): (FStreamV1[A, B], C) = (s, value)

    def consumer[A, B, C](fn: => C): FConsumer[A, B, A, B, C] = FConsumer{implicit stream => success(consume(fn))}
}
