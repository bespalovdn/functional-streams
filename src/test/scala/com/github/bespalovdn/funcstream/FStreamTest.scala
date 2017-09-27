package com.github.bespalovdn.funcstream

import java.util.concurrent.TimeoutException

import com.github.bespalovdn.funcstream.ext.FutureUtils._
import com.github.bespalovdn.funcstream.ext.TimeoutSupport
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.reflectiveCalls
import scala.util.Failure

@RunWith(classOf[JUnitRunner])
class FStreamTest extends UT
{
    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    "The test" should "check if read with timeout works" in {
        val connection = new TestConnection with TimeoutSupport

        val consumer: FConsumer[Int, Int, Unit] = FConsumer { stream =>
            stream.read(timeout = 100.millisecond) >> success()
        }

        val result: Future[Unit] = FStream(connection) consume consumer
        // check if it's not complete instantly:
        result.value should be (None)
        // wait for a while (more than timeout in consumer) and check if result completed with TimeoutException:
        Thread.sleep(200)
        inside (result.value) {
            case Some(Failure(t: Throwable)) => t shouldBe a [TimeoutException]
        }
    }

    it should "check if stream consumes input from producer, when subscribed only" in {
        val conn = new TestConnection
        val stream = FStream(conn)

        conn.pushNext()
        conn.pushNext()
        conn.getNextElem should be (3)

        val res1 = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res1.await should be (3)
        conn.getNextElem should be (4)

        conn.pushNext()
        conn.pushNext()
        conn.getNextElem should be (6)
        val res2 = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res2.await should be (6)
        conn.getNextElem should be (7)
    }

    it should "check if subscription logic works correctly" in {
        val conn = new TestConnection
        val stream = FStream(conn)
        val forkedStream = stream.fork()

        val res1 = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        val fRes1 = forkedStream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res1.await() should be (1)
        fRes1.await() should be (1)

        val res2 = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res2.await() should be (2)

        val res3 = forkedStream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res3.await() should be (3)

        val res4 = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        val fRes4 = forkedStream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res4.await() should be (4)
        fRes4.await() should be (4)
    }

    it should "check if fork works" in {
        val conn = new TestConnection
        val stream = FStream(conn)

        conn.pushNext()
        conn.getNextElem should be (2)

        val res1 = stream.fork() <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        val res2 = stream.fork() <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        val res3 = stream.fork() <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res1.await() should be (2)
        res2.await() should be (2)
        res3.await() should be (2)

        val res = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res.await() should be (3)

        val consumer45: FConsumer[Int, Int, Unit] = FConsumer {
            stream => for {
                a <- stream.read()
                _ <- Future{ a should be (4) }
                a <- stream.read()
                _ <- Future{ a should be (5) }
            } yield ()
        }

        val res4 = stream.fork() <=> consumer45
        val res5 = stream.fork() <=> consumer45
        conn.pushNext()
        conn.pushNext()
        res4.await()
        res5.await()
        conn.getNextElem should be (6)
    }

    it should "check if forked stream doesn't consume input until chained with consumer" in {
        val conn = new TestConnection
        val stream = FStream(conn)
        val forked = stream.fork()
        val forkedForked = forked.fork()

        val res1 = forkedForked <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res1.await() should be (1)

        val res2 = forked <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res2.await() should be (2)
    }

    it should "check if buffering functionality works" in {
        val conn = new TestConnection
        val stream = FStream(conn)
        val forked = stream.fork()

        val res1 = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res1.await() should be (1)
        val res2 = forked <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res2.await() should be (2)

        forked.preSubscribe()

        val res3 = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res3.await() should be (3)
        conn.pushNext()
        val res5 = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res5.await() should be (5)

        val res345 = forked <=> FConsumer[Int, Int, Unit]{
            stream => for {
                _ <- stream.read() >>= {elem => elem should be (3); success()}
                _ <- stream.read() >>= {elem => elem should be (4); success()}
                _ <- stream.read() >>= {elem => elem should be (5); success()}
            } yield ()
        }
        res345.await()

        val res6 = stream <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res6.await() should be (6)
        val res7 = forked <=> FConsumer[Int, Int, Int]{ stream => stream.read() }
        conn.pushNext()
        res7.await() should be (7)
    }

    it should "check if monadic consumer works" in {
        val conn = new TestConnection
        val stream = FStream(conn)

        val consumer: FConsumer[Int, Int, Int] = FConsumer { stream => stream.read() }

        val res: Future[Int] = stream <=> { consumer >> consumer }
        conn.pushNext()
        conn.pushNext()
        res.await() should be (2)

        val monadicPlus: FConsumer[Int, Int, Int] = for {
            a <- consumer
            b <- consumer
        } yield a + b
        val sum7 = stream <=> monadicPlus
        conn.pushNext()
        conn.pushNext()
        sum7.await() should be (7)
    }

    ignore should "check if invariance of FStream types works" in {
        val streamSS: FStream[String, String] = ???
        val streamOO: FStream[Object, Object] = ???

        val a: Future[Object] = streamSS.read()
        streamOO.write("Hello")

        val consumerOS: FConsumer[Object, String, Unit] = ???
        streamOO <=> consumerOS
        streamSS <=> consumerOS
    }
}
