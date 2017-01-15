package com.github.bespalovdn.funcstream

import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait FStreamV1[A, B]
{
    def read(timeout: Duration = null): Future[A]
    def write(elem: B): Future[Unit]
}
