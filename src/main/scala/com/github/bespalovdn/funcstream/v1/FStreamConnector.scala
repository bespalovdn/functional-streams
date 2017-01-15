package com.github.bespalovdn.funcstream.v1

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait FStreamConnector[A, B]
{
    def <=> [C, D, X](c: FConsumerV1[A, B, C, D, X]): Future[X]
    def <=> [C, D](pipe: FPipe[A, B, C, D]): FStreamV1[C, D] with FStreamConnector[C, D]
}

object FStreamConnector
{
    def apply[A, B](stream: FStreamV1[A, B]): FStreamConnector[A, B] = stream match {
        case stream: FStreamConnector[A@unchecked, B@unchecked] => stream
        case _ => new FStreamController(stream)
    }
}

private [funcstream] class FStreamController[A, B](upStream: FStreamV1[A, B])
    extends FStreamV1[A, B]
        with FStreamConnector[A, B]
{
    private val subscribers = mutable.ListBuffer.empty[Subscriber[A]]

    override def read(timeout: Duration): Future[A] = synchronized{
        val f = upStream.read(timeout)
        subscribers.synchronized{ subscribers.foreach(_.push(f)) }
        f
    }

    override def write(elem: B): Future[Unit] = synchronized{ upStream.write(elem) }

    override def <=> [C, D](pipe: FPipe[A, B, C, D]): FStreamV1[C, D] with FStreamConnector[C, D] = {
        val downStream = fork()
        val piped: FStreamV1[C, D] = pipe.apply(downStream)
        val pipedSubscription = new FStreamV1[C, D] with Subscription {
            override def read(timeout: Duration): Future[C] = piped.read(timeout)
            override def write(elem: D): Future[Unit] = piped.write(elem)
            override def subscribe(): Unit = downStream.subscribe()
            override def unsubscribe(): Unit = downStream.unsubscribe()
        }
        new FStreamController(pipedSubscription)
    }

    override def <=> [C, D, X](c: FConsumerV1[A, B, C, D, X]): Future[X] = {
        val downStream = fork()
        downStream <=> c
    }

    def subscribe(subscriber: Subscriber[A]): Unit = {
        subscribers.synchronized{ subscribers += subscriber }
        upStream match {
            case subscription: Subscription => subscription.subscribe()
            case _ =>
        }
    }
    def unsubscribe(subscriber: Subscriber[A]): Unit = {
        subscribers.synchronized{ subscribers -= subscriber }
        upStream match {
            case subscription: Subscription => subscription.unsubscribe()
            case _ =>
        }
    }

    private def fork(): DownStream[A, B] = new DownStream(this)
}

trait Subscription{
    def subscribe(): Unit
    def unsubscribe(): Unit
}

trait Subscriber[A]{
    def push(elem: Future[A]): Unit
}

private[funcstream] class DownStream[A, B](controller: FStreamController[A, B])
    extends FStreamV1[A, B]
    with FStreamConnector[A, B]
    with Subscription
    with Subscriber[A]
{
    private val readQueue = new mutable.Queue[Future[A]]()
    private val subscribersCount = new AtomicInteger(0)

    override def push(elem: Future[A]): Unit = readQueue.synchronized{readQueue.enqueue(elem)}

    override def read(timeout: Duration): Future[A] = {
        if(readQueue.synchronized{readQueue.isEmpty}){
            controller.read(timeout)
            readQueue.synchronized{readQueue.dequeue()}
        } else {
            readQueue.synchronized{readQueue.dequeue()}
        }
    }

    override def write(elem: B): Future[Unit] = controller.write(elem)

    override def <=>[C, D, X](c: FConsumerV1[A, B, C, D, X]): Future[X] = {
        subscribe()
        val f = c.apply(this).map(_._2)
        f.onComplete(_ => unsubscribe())
        f
    }

    override def <=>[C, D](pipe: FPipe[A, B, C, D]): FStreamV1[C, D] with FStreamConnector[C, D] = {
        controller <=> pipe
    }

    override def subscribe(): Unit = subscribersCount.synchronized{
        if(subscribersCount.getAndIncrement() == 0) {
            controller.subscribe(this)
        }
    }

    override def unsubscribe(): Unit = subscribersCount.synchronized{
        if(subscribersCount.decrementAndGet() == 0) {
            controller.unsubscribe(this)
            readQueue.synchronized(readQueue.clear())
        }
    }
}
