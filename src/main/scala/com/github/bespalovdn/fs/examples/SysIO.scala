package com.github.bespalovdn.fs.examples

import java.util.Scanner

import com.github.bespalovdn.fs
import com.github.bespalovdn.fs.FutureExtensions._
import com.github.bespalovdn.fs._
import com.github.bespalovdn.fs.impl.ClosableStreamImpl

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

object SysIORunner extends App
{
    SysIO()
}

trait SysIOTypes
{
    type Consumer[A] = fs.Consumer[String, String, String, String, A]
}

object SysIO extends SysIOTypes {
    import scala.concurrent.ExecutionContext.Implicits.global

    def invite: Consumer[Unit] = implicit stream => for {
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
    } yield consume()

    def echo: Consumer[Unit] = implicit stream => for {
        s <- stream.read()
        _ <- s.toUpperCase match {
            case "STOP" => success()
            case a => stream.write(a) >> echo(stream)
        }
    } yield consume()

    def buy: Consumer[Unit] = implicit stream => stream.write("BUY") >> stream.read() >>= {
        case "OK" => success(consume())
        case _ => println("Invalid response. Expected: OK"); buy(stream)
    }

    def log(msg: String): Consumer[Unit] = implicit stream => {
        println(msg)
        success(consume())
    }

    def apply(): Unit ={
        val stream = StdInOutStreamImpl.stdInOutStream
        val consumer = invite >>
            log("Echo server started. Print STOP in order to stop.") >>
            echo >>
            buy
        await(stream <=> consumer)
        println("DONE")
    }

    private def await[A](f: Future[A]): Unit = try {
        Await.result(f, Duration.Inf)
    } catch {
        case t: Throwable => println("Completed with error: " + t)
    }
}

object StdInOutStreamImpl
{
    private implicit def ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    //ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))

    private val sin = new Scanner(System.in)

    val stdInOutStream: Stream[String, String] = new ClosableStreamImpl[String, String] {
        override def read(timeout: Duration): Future[String] = checkClosed {
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
                p.success(())
            }
            p.future
        }
    }
}
