package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.mono.Publisher

trait Connection[A, B] extends Publisher[A]{
    def write(elem: B): Unit
}
