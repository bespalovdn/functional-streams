package com.github.bespalovdn.funcstream.ext

import java.io.Closeable

import com.github.bespalovdn.funcstream.{FStream, FutureExtensions}

import scala.concurrent.{Future, Promise}
import scala.util.Success

trait ClosableStream[A, B] extends FStream[A, B] with Closeable

trait ClosableStreamImpl[A, B] extends ClosableStream[A, B] with FutureExtensions
{
    private val _closed = Promise[Unit]

    val closed: Future[Unit] = _closed.future

    override def close(): Unit = {
        _closed.tryComplete(Success(()))
    }

    protected def checkClosed[X](f: => Future[X]): Future[X] ={
        import scala.concurrent.ExecutionContext.Implicits.global
        if(_closed.isCompleted)
            Future.failed(new StreamClosedException())
        else {
            f <|> (closed >>= (cause => Future.failed(new StreamClosedException())))
        }
    }
}

class StreamClosedException() extends Exception()
