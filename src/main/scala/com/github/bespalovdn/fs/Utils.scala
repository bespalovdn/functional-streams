package com.github.bespalovdn.fs

import com.github.bespalovdn.fs.Pipes._

import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.Success

trait FutureUtils
{
    implicit class FutureExt[A](val f: Future[A]){
        def >>= [B](fAB: A => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap fAB
        def >> [B](fB: => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap (_ => fB)
    }
}

object FutureUtils extends FutureUtils

trait PipeUtils extends FutureUtils
{
    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())
    def fail[A](cause: String): Future[A] = Future.failed(new ActionFailedException(cause))

    def consume[A, B]()(implicit s: Stream[A, B]): Consumed[A, B, Unit] = Consumed(s, ())
    def consume[A, B, C](value: C)(implicit s: Stream[A, B]): Consumed[A, B, C] = Consumed(s, value)
}

trait ClosableStream[A, B] extends Stream[A, B] with FutureUtils
{
    private val _closed = Promise[Unit]

    val closed: Future[Unit] = _closed.future

    override def close(): Future[Unit] = {
        _closed.tryComplete(Success(()))
        _closed.future
    }

    protected def checkClosed[A](f: => Future[A]): Future[A] ={
        import scala.concurrent.ExecutionContext.Implicits.global
        if(_closed.isCompleted)
            Future.failed(new StreamClosedException)
        else {
            val fClosed = closed >> Future.failed(new StreamClosedException)
            Future.firstCompletedOf(Seq(f, fClosed))
        }
    }
}