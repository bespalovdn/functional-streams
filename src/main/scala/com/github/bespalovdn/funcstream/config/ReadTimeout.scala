package com.github.bespalovdn.funcstream.config

import scala.concurrent.duration.Duration

case class ReadTimeout(duration: Duration)
object ReadTimeout
{
    val Inf = new ReadTimeout(duration = Duration.Inf)
}
