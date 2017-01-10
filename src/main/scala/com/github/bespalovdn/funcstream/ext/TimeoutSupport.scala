package com.github.bespalovdn.funcstream.ext

import java.util.concurrent.TimeoutException
import java.util.{Timer, TimerTask}

import com.github.bespalovdn.funcstream.FutureExtensions

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}

trait TimeoutSupport extends FutureExtensions
{
    def withTimeoutDo[A](timeout: Duration)(fn: => Future[A]): Future[A] = {
        if(timeout == null) fn
        else {
            val p = Promise[A]
            val task = new TimerTask {
                override def run(): Unit = p.failure(new TimeoutException(timeout.toString))
            }
            TimeoutSupport.timer.schedule(task, timeout.toMillis)

            implicit def ec = scala.concurrent.ExecutionContext.global

            fn <|> p.future
        }
    }

    def waitFor(timeout: Duration): Future[Unit] = {
        if(timeout == null) Future.successful(())
        else {
            val p = Promise[Unit]
            val task = new TimerTask {
                override def run(): Unit = p.success(())
            }
            TimeoutSupport.timer.schedule(task, timeout.toMillis)
            p.future
        }
    }
}

object TimeoutSupport
{
    private val timer = new Timer(true)
}