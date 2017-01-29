package com.github.bespalovdn.funcstream.impl

import java.util.concurrent.atomic.AtomicReference

import com.github.bespalovdn.funcstream.mono.{Publisher, Subscriber}

private[funcstream]
trait PublisherProxy[A, B] extends Subscriber[A] with Publisher[B]
{
    def upstream: Publisher[A]

    private val subscribers = new AtomicReference(Vector.empty[Subscriber[B]])

    override def subscribe(subscriber: Subscriber[B]): Unit = subscribers.synchronized {
        val subs = subscribers.get()
        if(subs.isEmpty){
            upstream.subscribe(this)
        }
        subscribers.set(subs :+ subscriber)
    }

    override def unsubscribe(subscriber: Subscriber[B]): Unit = subscribers.synchronized{
        val subs = subscribers.get() filterNot (_ eq subscriber)
        if(subs.isEmpty){
            upstream.unsubscribe(this)
        }
        subscribers.set(subs)
    }

    def forEachSubscriber(fn: Subscriber[B] => Unit): Unit = subscribers.get.foreach(fn)
}