package com.github.bespalovdn.funcstream

trait Subscriber[A]{
    def push(elem: A)
}
