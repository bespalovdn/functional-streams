package com.github.bespalovdn.funcstream

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}

trait FStream[A, B]
{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]

    def <=> [C, D](pipe: FPipe[A, B, C, D]): FStream[C, D] = {
        val downStream = fork()

    }

    def <=> [C, D, X](c: FConsumer[A, B, C, D, X]): Future[X] = {
        val completion = Promise[Unit]
        val downStream = fork(completion.future)
        val f = c.apply(downStream).map(_._2)
        f.onComplete(_ => completion.success(()))
        f
    }

    private val readQueues = ListBuffer.empty[ConcurrentLinkedQueue[Future[A]]]

    private def upStream = this

    private def fork(completion: Future[_]): FStream[A, B] = {
        val stream = new DownStream
        completion.onComplete(_ => stream.close())
        stream
    }

    private class DownStream extends FStream[A, B] with Closeable{
        private val closed = new AtomicBoolean(false)
        private val readQueue = new ConcurrentLinkedQueue[Future[A]]()

        readQueues.synchronized{ readQueues += readQueue }

        override def write(elem: B): Future[Unit] = {
            if(closed.get())
                fail(new StreamClosedException)
            else
                upStream.synchronized{ upStream.write(elem) }
        }

        override def read(timeout: Duration): Future[A] = {
            if(closed.get()){
                fail(new StreamClosedException)
            } else {
                readQueue.poll() match {
                    case null =>
                        val f = upStream.synchronized(upStream.read(timeout))
                        readQueues.synchronized{ readQueues.foreach(_ offer f) }
                        readQueue.poll()
                    case elem =>
                        elem
                }
            }
        }

        override def close(): Unit = {
            closed.set(true)
            readQueues.synchronized{ readQueues -= readQueue }
        }
    }
}
