package com.github.bespalovdn.fs

import com.github.bespalovdn.fs.Pipes._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

trait FutureUtils
{
    implicit class FutureOps[A](val f: Future[A]){
        def >>= [B](fAB: A => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap fAB
        def >> [B](fB: => Future[B])(implicit e: ExecutionContext): Future[B] = f flatMap (_ => fB)
        def <|> (f2: Future[A])(implicit e: ExecutionContext): Future[A] = Future.firstCompletedOf(Seq(f, f2))
    }

    def repeatOnFail[A](f: => Future[A])(implicit e: ExecutionContext): Future[A] = f.recoverWith{case _ => repeatOnFail(f)}
}

object FutureUtils extends FutureUtils

trait PipeUtils extends FutureUtils
{
    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())
    def fail[A](cause: String): Future[A] = Future.failed(new ActionFailedException(cause))

    def consume[A, B]()(implicit s: Stream[A, B]): Consumed[A, B, Unit] = Consumed(s, ())
    def consume[A, B, C](value: C)(implicit s: Stream[A, B]): Consumed[A, B, C] = Consumed(s, value)

    def consumer[A, B, C](fn: => C): Consumer[A, B, A, B, C] = implicit stream => success(consume(fn))
}

trait ClosableStream[A, B] extends Stream[A, B] with FutureUtils
{
    private val _closed = Promise[Throwable]

    val closed: Future[Throwable] = _closed.future

    override def close(cause: Throwable): Future[Unit] = {
        import scala.concurrent.ExecutionContext.Implicits.global
        _closed.tryComplete(Success(cause))
        _closed.future.map(_ => ())
    }

    protected def checkClosed[A](f: => Future[A]): Future[A] ={
        import scala.concurrent.ExecutionContext.Implicits.global
        if(_closed.isCompleted)
            Future.failed(new StreamClosedException(closed.value.get.get))
        else {
            f <|> (closed >>= (cause => Future.failed(new StreamClosedException(cause))))
        }
    }
}