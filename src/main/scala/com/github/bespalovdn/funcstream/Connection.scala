package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.mono.Publisher

import scala.concurrent.{Future, Promise}

trait Connection[R, W] extends Publisher[R]
{
    def write(elem: W): Future[Unit]

    def close(): Future[Unit] = { pClosed.trySuccess(()); closed }
    def closed: Future[Unit] = pClosed.future

    private val pClosed = Promise[Unit]
}
