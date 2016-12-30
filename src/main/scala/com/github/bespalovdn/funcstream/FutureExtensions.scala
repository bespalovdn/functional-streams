package com.github.bespalovdn.funcstream

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

trait FutureExtensions
{
    implicit class FutureOps[A](val f: Future[A]){
        def >>= [B](fAB: A => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap fAB
        def >> [B](fB: => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap (_ => fB)
        def <|> (f2: Future[A])(implicit e: ExecutionContext): Future[A] = Future.firstCompletedOf(Seq(f, f2))
    }

    implicit def convert2Unit[A](f: Future[A]): Future[Unit] = f >> Future.successful()
}

object FutureExtensions extends FutureExtensions
{
    def fork[A](f: => Future[A]): Future[Future[A]] = Future.successful(f)
}