package com.github.bespalovdn.funcstream

trait Publisher[A]{
    def subscribe(subscriber: Subscriber[A])
    def unsubscribe(subscriber: Subscriber[A])
}
