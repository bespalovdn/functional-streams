package com.github.bespalovdn.funcstream.impl

import java.util.{HashMap => JHashMap}

import com.github.bespalovdn.funcstream.mono.{Publisher, Subscriber}

private[funcstream]
trait PublisherProxy[A, B] extends Subscriber[A] with Publisher[B]
{
    def upstream: Publisher[A]

    private val subscribers = new JHashMap[Subscriber[B], Int]() // subscriber -> subscribe counter

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

    def forEachSubscriber(fn: Subscriber[B] => Unit): Unit = subscribers.synchronized{
        subscribers.keySet().forEach(subscriber => fn(subscriber))
    }
}
