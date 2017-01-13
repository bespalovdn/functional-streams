package com.github.bespalovdn.funcstream

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait FStream[A, B]
{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]

    def <=> [C, D](pipe: FPipe[A, B, C, D]): FStream[C, D] = {
        val downStream = fork()
        pipe.apply(downStream)
    }

    def <=> [C, D, X](c: FConsumer[A, B, C, D, X]): Future[X] = {
        val downStream = fork()
        c.apply(downStream).map(_._2)
    }

    private[funcstream] def fork(): FStream[A, B] = new DownStream(this)

    private val downReadQueues = mutable.ListBuffer.empty[mutable.Queue[Future[A]]]
}

private[funcstream] class DownStream[A, B](upStream: FStream[A, B]) extends FStream[A, B] {
    private val readQueue = new mutable.Queue[Future[A]]()
    private val subscribersCount = new AtomicInteger(0)

    override def write(elem: B): Future[Unit] = {
        upStream.synchronized{ upStream.write(elem) }
    }

    override def read(timeout: Duration): Future[A] = {
        if(readQueue.isEmpty){
            val f = upStream.synchronized(upStream.read(timeout))
            downReadQueues.synchronized{ downReadQueues.foreach(_.enqueue(f)) }
            readQueue.dequeue()
        } else {
            readQueue.dequeue()
        }
    }

    override def <=> [C, D, X](c: FConsumer[A, B, C, D, X]): Future[X] = {
        subscribe()
        val downStream = fork()
        val f = c.apply(downStream).map(_._2)
        f.onComplete(_ => unsubscribe())
        f
    }

    def subscribe(): Unit = subscribersCount.synchronized{
        if(subscribersCount.incrementAndGet() > 0) {
            downReadQueues.synchronized {downReadQueues += readQueue}
        }
    }

    def unsubscribe(): Unit = subscribersCount.synchronized{
        if(subscribersCount.decrementAndGet() == 0) {
            downReadQueues.synchronized {downReadQueues -= readQueue}
            readQueue.clear()
        }
    }

    override def <=>[C, D, X](c: FConsumer[A, B, C, D, X]): Future[X] = {
        subscribe()
        val f = super.<=>(c)
        f.onComplete(_ => unsubscribe())
        f
    }
}