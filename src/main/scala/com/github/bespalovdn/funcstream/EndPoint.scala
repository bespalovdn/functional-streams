package com.github.bespalovdn.funcstream

trait EndPoint[A, B] extends Publisher[A]{
    def write(elem: B): Unit
}
