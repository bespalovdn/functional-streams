package com.github.bespalovdn.funcstream

trait Pipe[A, B, C, D] extends (Stream[A, B] => Stream[C, D])
