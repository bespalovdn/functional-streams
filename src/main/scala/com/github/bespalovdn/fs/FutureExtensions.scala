package com.github.bespalovdn.fs

import scala.concurrent.{ExecutionContext, Future}

trait FutureExtensions
{
    implicit class FutureOps[A](val f: Future[A]){
        def >>= [B](fAB: A => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap fAB
        def >> [B](fB: => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap (_ => fB)
        def <|> (f2: Future[A])(implicit e: ExecutionContext): Future[A] = Future.firstCompletedOf(Seq(f, f2))
    }
}

object FutureExtensions extends FutureExtensions