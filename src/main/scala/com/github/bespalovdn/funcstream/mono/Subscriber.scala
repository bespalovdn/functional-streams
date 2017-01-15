package com.github.bespalovdn.funcstream.mono

trait Subscriber[A]{
    def push(elem: A)
}
