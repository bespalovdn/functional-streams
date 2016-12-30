package com.github.bespalovdn.funcstream

trait Pipe[A, B, C, D] extends (FStream[A, B] => FStream[C, D])
