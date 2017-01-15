package com.github.bespalovdn.funcstream

import scala.concurrent.Future

package object v2 {
    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())

    def fail[A](t: Throwable): Future[A] = Future.failed(t)
}
