package com.github.bespalovdn.funcstream

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait FStreamConnector[A, B]
{
    def <=> [C, D, X](c: FConsumer[A, B, C, D, X]): Future[X]
    def <=> [C, D](pipe: FPipe[A, B, C, D]): FStream[C, D] with FStreamConnector[C, D]
}

object FStreamConnector
{
    def apply[A, B](stream: FStream[A, B]): FStreamConnector[A, B] = stream match {
        case stream: FStreamConnector[A@unchecked, B@unchecked] => stream
        case _ => new FStreamController(stream)
    }
}

private [funcstream] class FStreamController[A, B](upStream: FStream[A, B])
    extends FStream[A, B]
        with FStreamConnector[A, B]
{
    private val downReadQueues = mutable.ListBuffer.empty[mutable.Queue[Future[A]]]

    override def read(timeout: Duration): Future[A] = synchronized{
        val f = upStream.read(timeout)
        downReadQueues.synchronized{ downReadQueues.foreach(q => q.synchronized{ q.enqueue(f) }) }
        f
    }

    override def write(elem: B): Future[Unit] = synchronized{ upStream.write(elem) }

    override def <=> [C, D](pipe: FPipe[A, B, C, D]): FStream[C, D] with FStreamConnector[C, D] = {
        val downStream = pipe.apply(fork())
        new FStreamController(downStream)
    }

    override def <=> [C, D, X](c: FConsumer[A, B, C, D, X]): Future[X] = {
        val downStream = fork()
        downStream <=> c
    }

    def subscribe(downQueue: mutable.Queue[Future[A]]): Unit = downReadQueues.synchronized{ downReadQueues += downQueue }
    def unsubscribe(downQueue: mutable.Queue[Future[A]]): Unit = downReadQueues.synchronized{ downReadQueues -= downQueue }

    private def fork(): DownStream[A, B] = new DownStream(this)
}

private[funcstream] class DownStream[A, B](upStream: FStreamController[A, B])
    extends FStream[A, B]
        with FStreamConnector[A, B]
{
    private val readQueue = new mutable.Queue[Future[A]]()
    private val subscribersCount = new AtomicInteger(0)

    override def read(timeout: Duration): Future[A] = {
        if(readQueue.synchronized{readQueue.isEmpty}){
            upStream.read(timeout)
            readQueue.synchronized{readQueue.dequeue()}
        } else {
            readQueue.synchronized{readQueue.dequeue()}
        }
    }

    override def write(elem: B): Future[Unit] = upStream.write(elem)

    override def <=>[C, D, X](c: FConsumer[A, B, C, D, X]): Future[X] = {
        subscribe()
        val f = c.apply(this).map(_._2)
        f.onComplete(_ => unsubscribe())
        f
    }

    override def <=>[C, D](pipe: FPipe[A, B, C, D]): FStream[C, D] with FStreamConnector[C, D] = {
        upStream <=> pipe
    }

    private def subscribe(): Unit = subscribersCount.synchronized{
        if(subscribersCount.getAndIncrement() == 0) {
            upStream.subscribe(readQueue)
        }
    }

    private def unsubscribe(): Unit = subscribersCount.synchronized{
        if(subscribersCount.decrementAndGet() == 0) {
            upStream.unsubscribe(readQueue)
            readQueue.clear()
        }
    }
}
