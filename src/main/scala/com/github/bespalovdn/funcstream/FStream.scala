package com.github.bespalovdn.funcstream

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait FStream[A, B]
{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]

    def <=> [C, D](p: FPipe[A, B, C, D]): FStream[C, D] = p.apply(this)
    def <=> [C, D, X](c: FConsumer[A, B, C, D, X]): Future[X] = c.apply(this).map(_._2)
}
