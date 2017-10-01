package com.github.bespalovdn.funcstream.ext

import java.util.TimerTask
import java.util.concurrent._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.Success

trait TimeoutSupport
{
    def withTimeout[A](timeout: Duration)(origin: Promise[A]): Future[A] = {
        if(timeout == null) origin.future
        else {
            val originFuture = origin.future
            val task = new Runnable {
                override def run(): Unit = {
                    origin.tryFailure(new TimeoutException(timeout.toString))
                }
            }
            val timeoutFuture = TimeoutSupport.scheduledExecutor.schedule(task, timeout.toMillis, TimeUnit.MILLISECONDS)
            originFuture andThen {
                case Success(_) => timeoutFuture.cancel(false)
                case _ => // do nothing
            }
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
            //TODO: set name for the pool:
            val t = Executors.defaultThreadFactory().newThread(r)
            t.setDaemon(true)
            t
        }
    }
}