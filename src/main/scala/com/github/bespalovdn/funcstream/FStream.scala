package com.github.bespalovdn.funcstream

import java.util.concurrent.ConcurrentLinkedQueue

import com.github.bespalovdn.funcstream.impl.ClosableStreamImpl

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait FStream[A, B]
{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]

    def <=> [C, D](p: FPipe[A, B, C, D]): FStream[C, D] = p.apply(this)

    def <=> [C, D, X](c: => FConsumer[A, B, C, D, X]): Future[X] = {
        import scala.concurrent.ExecutionContext.Implicits.global
        val downStream = new DownStream(this)
        val f = c(downStream)
        f.onComplete{_ => downStream.close()}
        f.map(_._2)
    }

    private lazy val readQueues = ListBuffer.empty[ConcurrentLinkedQueue[Future[A]]]

    private class DownStream(upstream: FStream[A, B]) extends ClosableStreamImpl[A, B]{
        import scala.concurrent.ExecutionContext.Implicits.global
        val readQueue = new ConcurrentLinkedQueue[Future[A]]()

        readQueues.synchronized{ readQueues += this.readQueue }
        this.closed.onComplete{_ => readQueues.synchronized{ readQueues -= this.readQueue }}

        override def write(elem: B): Future[Unit] = checkClosed{ upstream.synchronized{ upstream.write(elem) } }
        override def read(timeout: Duration): Future[A] = checkClosed{
            this.readQueue.poll() match {
                case null =>
                    val f = upstream.synchronized(upstream.read(timeout))
                    readQueues.foreach(_ offer f)
                    Option(this.readQueue.poll()).getOrElse(this.read())
                case elem =>
                    elem
            }
        }
    }
}
