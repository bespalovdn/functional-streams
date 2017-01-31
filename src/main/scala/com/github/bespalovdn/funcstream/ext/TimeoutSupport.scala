package com.github.bespalovdn.funcstream.ext

import java.util.TimerTask
import java.util.concurrent._

import com.github.bespalovdn.funcstream.ext.FutureUtils._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}

trait TimeoutSupport
{
    def withTimeout[A](timeout: Duration)(fn: => Future[A]): Future[A] = {
        if(timeout == null) fn
        else {
            val p = Promise[A]
            val task = new Runnable {
                override def run(): Unit = p.failure(new TimeoutException(timeout.toString))
            }
            val timeoutFuture = TimeoutSupport.scheduledExecutor.schedule(task, timeout.toMillis, TimeUnit.MILLISECONDS)
            fn.onComplete(_ => timeoutFuture.cancel(false))
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
            TimeoutSupport.scheduledExecutor.schedule(task, timeout.toMillis, TimeUnit.MILLISECONDS)
            p.future
        }
    }
}

object TimeoutSupport extends TimeoutSupport
{
    private val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1, newThreadFactory())

    private def newThreadFactory() = new ThreadFactory () {
        override def newThread(r: Runnable): Thread = {
            val t = Executors.defaultThreadFactory().newThread(r)
            t.setDaemon(true)
            t
        }
    }
}