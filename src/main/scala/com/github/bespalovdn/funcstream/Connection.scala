package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.mono.Publisher

import scala.concurrent.Future

trait Connection[A, B] extends Publisher[A]{
    def write(elem: B): Future[Unit]
}
