package com.github.bespalovdn.funcstream

trait FPipe[A, B, C, D] extends (FStream[A, B] => FStream[C, D])

object FPipe
{
    def apply[A, B, C, D](fn: FStream[A, B] => FStream[C, D]): FPipe[A, B, C, D] = new FPipe[A, B, C, D] {
        override def apply(stream: FStream[A, B]): FStream[C, D] = fn(stream)
    }
}