package com.github.bespalovdn.fs

import com.github.bespalovdn.fs.Pipes._

import scala.concurrent.{ExecutionContext, Future}

trait FutureUtils
{
    implicit class FutureExt[A](val f: Future[A]){
        def >>= [B](fAB: A => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap fAB
        def >> [B](fB: => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap (_ => fB)
    }
}

trait PipeUtils extends FutureUtils
{
    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())
    def fail[A](cause: String): Future[A] = Future.failed(new ActionFailedException(cause))

    def fork[A, B, C](c: Consumer[A, B, C])(implicit e: ExecutionContext): Consumer[A, B, Unit] = stream => {
        Future{
            c(stream)
        }
        success()
    }
}
