package com.github.bespalovdn.fs.examples

import java.util.Scanner

import com.github.bespalovdn.fs.{PipeUtils, FutureUtils, Stream, StreamClosedException}
import com.github.bespalovdn.fs.Pipes._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Success

object SysIO extends PipeUtils {
    import scala.concurrent.ExecutionContext.Implicits.global

    //    def keepalive: Consumer[String, String, Unit] = stream => {
    //        stream.write("KEEPALIVE") >>
    //            {stream.read() >>= {case "OK" => success()}} >>
    ////            sleep(1 minute) >>
    //            keepalive(stream)
    //    }

    def invite: Consumer[String, String, Unit] = stream => for {
        _ <- stream.write("INVITE")
        res <- stream.read()
        res <- res match {
            case "TRYING" => stream.read()
            case _ => success(res)
        }
        _ <- res match {
            case "OK" => success()
            case r => fail(s"Unexpected result: $r. Expected: OK")
        }
    } yield ()

    def echo: Consumer[String, String, Unit] = stream => for {
        s <- stream.read()
        _ <- s.toUpperCase match {
            case "STOP" => success()
            case a => stream.write(a) >> echo(stream)
        }
    } yield ()

    def buy: Consumer[String, String, Unit] = stream => stream.write("BUY") >> stream.read() >>= {
        case "OK" => success()
        case _ => println("Invalid response. Expected: OK"); buy(stream)
    }

    def log(msg: String): Consumer[String, String, Unit] = stream => {
        println(msg)
        success()
    }

    def apply(): Unit ={
        val stream = new StdInOutStreamImpl {}.stdInOutStream
        val consumer = invite >>
            //            fork(keepalive) >>
            log("Echo server started. Print STOP in order to stop.") >>
            echo >>
            buy
        await(stream >> consumer)
        println("DONE")
    }

    private def await[A](f: Future[A]): Unit = try {
        Await.result(f, Duration.Inf)
    } catch {
        case t: Throwable => println("Completed with error: " + t)
    }
}

trait StdInOutStreamImpl
{
    private implicit def ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    //ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))

    private val sin = new Scanner(System.in)

    def stdInOutStream: Stream[String, String] = new Stream[String, String] with FutureUtils {
        private val closed = Promise[Unit]

        private def checkClosed[A](f: => Future[A]): Future[A] ={
            if(closed.isCompleted)
                Future.failed(new StreamClosedException)
            else {
                val fClosed = closed.future >> Future.failed(new StreamClosedException)
                Future.firstCompletedOf(Seq(f, fClosed))
            }
        }

        override def read(): Future[String] = checkClosed {
            val p = Promise[String]
            Future{
                val in = sin.next()
                p.success(in)
            }
            p.future
        }

        override def write(elem: String): Future[Unit] = checkClosed {
            val p = Promise[Unit]
            Future{
                println(elem)
                p.success()
            }
            p.future
        }

        override def close(): Future[Unit] = {
            closed.tryComplete(Success())
            closed.future
        }
    }
}