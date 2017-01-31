package com.github.bespalovdn.funcstream.ext

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

trait FutureUtils
{
    implicit class FutureOps[A](val f: Future[A]){
        def >>= [B](fAB: A => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap fAB
        def >> [B](fB: => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap (_ => fB)
        def <|> (f2: Future[A])(implicit e: ExecutionContext): Future[A] = Future.firstCompletedOf(Seq(f, f2))
    }

    implicit def convert2Unit[A](f: Future[A]): Future[Unit] = {
        import scala.concurrent.ExecutionContext.Implicits.global
        f >> Future.successful(())
    }

    def success[A](value: => A)(implicit ec: ExecutionContext): Future[A] = Future(value)
    def success(): Future[Unit] = {
        import scala.concurrent.ExecutionContext.Implicits.global
        success(())
    }

    def fail[A](t: Throwable): Future[A] = Future.failed(t)

    def fork[A](f: => Future[A])(implicit ec: ExecutionContext): Future[Future[A]] = success(f)
}

object FutureUtils extends FutureUtils