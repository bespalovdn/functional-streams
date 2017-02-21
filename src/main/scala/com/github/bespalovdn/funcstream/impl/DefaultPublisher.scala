package com.github.bespalovdn.funcstream.impl

import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator

import com.github.bespalovdn.funcstream.mono.{Publisher, Subscriber}

trait DefaultPublisher[A] extends Publisher[A]{
    private val subscribers = new AtomicReference(Vector.empty[Subscriber[A]])

    override def subscribe(subscriber: Subscriber[A]): Unit = subscribers.updateAndGet(new UnaryOperator[Vector[Subscriber[A]]] {
        override def apply(subs: Vector[Subscriber[A]]): Vector[Subscriber[A]] = subs :+ subscriber
    })

    override def unsubscribe(subscriber: Subscriber[A]): Unit = subscribers.updateAndGet(new UnaryOperator[Vector[Subscriber[A]]] {
        override def apply(subs: Vector[Subscriber[A]]): Vector[Subscriber[A]] = subs filterNot (_ eq subscriber)
    })

    def forEachSubscriber(fn: Subscriber[A] => Unit): Unit = subscribers.get.foreach(fn)
}
