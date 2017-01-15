package com.github.bespalovdn.funcstream

trait FPipe[A, B, C, D] extends (FStreamV1[A, B] => FStreamV1[C, D])

object FPipe
{
    def apply[A, B, C, D](fn: FStreamV1[A, B] => FStreamV1[C, D]): FPipe[A, B, C, D] = new FPipe[A, B, C, D] {
        override def apply(upStream: FStreamV1[A, B]): FStreamV1[C, D] = fn(upStream)
    }
}