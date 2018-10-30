package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.mono.Publisher

import scala.concurrent.{Future, Promise}

trait Connection[R, W] extends Publisher[R] with Resource
{
    def write(elem: W): Future[Unit]
}

trait Resource
{
    private val pClosed = Promise[Unit]

    def close(): Future[Unit] = { pClosed.trySuccess(()); closed }
    def closed: Future[Unit] = pClosed.future
}