package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.mono.Publisher

import scala.concurrent.Future

trait Connection[R, W] extends Publisher[R]{
    def write(elem: W): Future[Unit]
}
