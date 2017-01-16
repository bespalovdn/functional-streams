package com.github.bespalovdn.funcstream.examples

import java.util.Scanner

import com.github.bespalovdn.funcstream._
import com.github.bespalovdn.funcstream.examples.sip.SipProtocolException
import com.github.bespalovdn.funcstream.ext.ClosableStreamImpl

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

object SysIORunner extends App
{
    SysIO()
}

trait SysIOTypes
{
    type Consumer[A] = FConsumer[String, String, String, String, A]
}

object SysIO extends SysIOTypes with FutureExtensions {
    import scala.concurrent.ExecutionContext.Implicits.global

    def invite: Consumer[Unit] = FConsumer { implicit stream =>
        for {
            _ <- stream.write("INVITE")
            res <- stream.read()
            res <- res match {
                case "TRYING" => stream.read()
                case _ => success(res)
            }
            _ <- res match {
                case "OK" => success()
                case r => fail(new SipProtocolException(s"Unexpected result: $r. Expected: OK"))
            }
        } yield consume()
    }

    def echo: Consumer[Unit] = FConsumer { implicit stream =>
        for {
            s <- stream.read()
            _ <- s.toUpperCase match {
                case "STOP" => success()
                case a => stream.write(a) >> echo.apply(stream)
            }
        } yield consume()
    }

    def bue: Consumer[Unit] = FConsumer { implicit stream =>
        stream.write("BUY") >> stream.read() >>= {
            case "OK" => success(consume())
            case _ => println("Invalid response. Expected: OK"); bue.apply(stream)
        }
    }

    def log(msg: String): Consumer[Unit] = FConsumer { implicit stream =>
        println(msg)
        success(consume())
    }

    def apply(): Unit ={
        val stream = StdInOutStreamImpl.stdInOutStream
        val consumer = invite >>
            log("Echo server started. Print STOP in order to stop.") >>
            echo >>
            bue
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

    val stdInOutStream: FStream[String, String] = new ClosableStreamImpl[String, String] {
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
