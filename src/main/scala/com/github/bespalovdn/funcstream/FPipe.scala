package com.github.bespalovdn.funcstream

//TODO: shouldn't be created out of FPipe factory method
trait FPipe[A, B, C, D] extends (FStream[A, B] => FStream[C, D])

object FPipe
{
    def apply[A, B, C, D](fn: FStream[A, B] => FStream[C, D]): FPipe[A, B, C, D] = new FPipe[A, B, C, D] {
        override def apply(upStream: FStream[A, B]): FStream[C, D] = fn(upStream.fork())
    }
}