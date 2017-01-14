package com.github.bespalovdn.funcstream

import scala.concurrent.Future
import scala.concurrent.duration.Duration

//TODO: experimental entities. to be removed.
trait Source[A]{
    def read(timeout: Duration = null): Future[A]
}

trait Sink[A]{
    def write(elem: A, timeout: Duration = null): Future[Unit]
}

trait Consumer[A] extends (Source[A] => Future[A])
trait Producer[A] extends (Sink[A] => Future[Unit])

