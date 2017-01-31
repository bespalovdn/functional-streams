package com.github.bespalovdn

import scala.concurrent.{ExecutionContext, Future}

package object funcstream {
    def success[A](value: => A)(implicit ec: ExecutionContext): Future[A] = Future(value)
    def success(): Future[Unit] = {
        import scala.concurrent.ExecutionContext.Implicits.global
        success(())
    }

    def fail[A](t: Throwable): Future[A] = Future.failed(t)
}
