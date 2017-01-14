package com.github.bespalovdn.funcstream

import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait FStream[A, B]
{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]
}
