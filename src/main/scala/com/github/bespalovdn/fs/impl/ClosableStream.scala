package com.github.bespalovdn.fs.impl

import java.io.Closeable

import com.github.bespalovdn.fs.{FutureExtensions, Stream, StreamClosedException}

import scala.concurrent.{Future, Promise}
import scala.util.Success

trait ClosableStream[A, B] extends Stream[A, B] with Closeable

trait ClosableStreamImpl[A, B] extends ClosableStream[A, B] with FutureExtensions
{
    private val _closed = Promise[Unit]

    val closed: Future[Unit] = _closed.future

    override def close(): Unit = {
        _closed.tryComplete(Success(()))
    }

    protected def checkClosed[A](f: => Future[A]): Future[A] ={
        import scala.concurrent.ExecutionContext.Implicits.global
        if(_closed.isCompleted)
            Future.failed(new StreamClosedException())
        else {
            f <|> (closed >>= (cause => Future.failed(new StreamClosedException())))
        }
    }
}