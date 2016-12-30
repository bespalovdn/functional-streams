package com.github.bespalovdn.funcstream

trait FPipe[A, B, C, D] extends (FStream[A, B] => FStream[C, D])
