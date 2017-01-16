package com.github.bespalovdn.funcstream.mono

import scala.util.Try

trait Subscriber[A]{
    def push(elem: Try[A])
}
