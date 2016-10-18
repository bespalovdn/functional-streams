package com.github.bespalovdn.fs.impl

import com.github.bespalovdn.fs.{FutureExtensions, Stream, StreamClosedException}

import scala.concurrent.{Future, Promise}
import scala.util.Success

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