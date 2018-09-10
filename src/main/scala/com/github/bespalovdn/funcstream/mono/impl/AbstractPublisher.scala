package com.github.bespalovdn.funcstream.mono.impl

import java.util.concurrent.ConcurrentHashMap
import java.util.{HashSet => JHashSet}

import com.github.bespalovdn.funcstream.mono.{Publisher, Subscriber}

trait AbstractPublisher[A] extends Publisher[A]
{
    private val subscribers = new ConcurrentHashMap[Subscriber[A], Int] // subscriber -> subscribe counter

    override def subscribe(subscriber: Subscriber[A]): Unit = subscribers.synchronized {
        subscribers.compute(subscriber, (k, v) => v + 1)
    }

    override def unsubscribe(subscriber: Subscriber[A]): Unit = subscribers.synchronized{
        val counter = subscribers.compute(subscriber, (k, v) => v - 1)
        if(counter < 1)
            subscribers.remove(subscriber)
    }

    def forEachSubscriber(fn: Subscriber[A] => Unit): Unit = {
        val keys = new JHashSet(subscribers.keySet())
        keys.forEach(subscriber => fn(subscriber))
    }
}
