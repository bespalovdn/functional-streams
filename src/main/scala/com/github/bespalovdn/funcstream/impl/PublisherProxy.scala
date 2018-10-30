package com.github.bespalovdn.funcstream.impl

import java.util.concurrent.ConcurrentHashMap
import java.util.{HashSet => JHashSet}

import com.github.bespalovdn.funcstream.Resource
import com.github.bespalovdn.funcstream.mono.{Publisher, Subscriber}

import scala.concurrent.Future

private[funcstream]
trait PublisherProxy[A, B] extends Subscriber[A] with Publisher[B] with Resource
{
    def upstream: Publisher[A] with Resource

    private val subscribers = new ConcurrentHashMap[Subscriber[B], Int]() // subscriber -> subscribe counter

    override def subscribe(subscriber: Subscriber[B]): Unit = subscribers.synchronized {
        if(subscribers.isEmpty)
            upstream.subscribe(this)
        subscribers.compute(subscriber, (k, v) => v + 1)
    }

    override def unsubscribe(subscriber: Subscriber[B]): Unit = subscribers.synchronized{
        val counter = subscribers.compute(subscriber, (k, v) => v - 1)
        if(counter < 1)
            subscribers.remove(subscriber)
        if(subscribers.isEmpty)
            upstream.unsubscribe(this)
    }

    def forEachSubscriber(fn: Subscriber[B] => Unit): Unit = {
        val keys = new JHashSet(subscribers.keySet())
        keys.forEach(subscriber => fn(subscriber))
    }

    override def close(): Future[Unit] = upstream.close()
    override def closed: Future[Unit] = upstream.closed
}
