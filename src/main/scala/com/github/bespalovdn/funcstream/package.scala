package com.github.bespalovdn

import scala.concurrent.Future

package object funcstream {
    def success[A](value: => A): Future[A] = Future(value)
    def success(): Future[Unit] = success(())

    def fail[A](t: Throwable): Future[A] = Future.failed(t)
}
