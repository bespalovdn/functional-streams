package com.github.bespalovdn.fs

import scala.concurrent.{Future, Promise}
import scala.util.Success

trait PipeUtils extends FutureExtensions
{
    def success[A](value: A): Future[A] = Future.successful(value)
    def success(): Future[Unit] = success(())
    def fail[A](cause: String): Future[A] = Future.failed(new ActionFailedException(cause))

    def consume[A, B]()(implicit s: Stream[A, B]): Consumed[A, B, Unit] = Consumed(s, ())
    def consume[A, B, C](value: C)(implicit s: Stream[A, B]): Consumed[A, B, C] = Consumed(s, value)

    def consumer[A, B, C](fn: => C): Consumer[A, B, A, B, C] = implicit stream => success(consume(fn))
}

trait ClosableStream[A, B] extends Stream[A, B] with FutureExtensions
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