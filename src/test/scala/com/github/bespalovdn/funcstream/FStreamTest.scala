package com.github.bespalovdn.funcstream

import java.util.concurrent.TimeoutException

import com.github.bespalovdn.funcstream.ext.FutureUtils._
import com.github.bespalovdn.funcstream.ext.TimeoutSupport
import com.github.bespalovdn.funcstream.impl.DefaultPublisher
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.reflectiveCalls
import scala.util.{Failure, Success}

@RunWith(classOf[JUnitRunner])
class FStreamTest extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Inside
{
    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    class TestConnection extends Connection[Int, Int] with DefaultPublisher[Int]{
        private var nextElem: Int = 1
        override def write(elem: Int): Future[Unit] = success()
        def pushNext(): Unit = {
            forEachSubscriber(s => s.push(Success(nextElem)))
            nextElem += 1
        }
        def getNextElem: Int = nextElem
    }

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

        val res1 = stream <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res1.await should be (3)
        conn.getNextElem should be (4)

        conn.pushNext()
        conn.pushNext()
        conn.getNextElem should be (6)
        val res2 = stream <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res2.await should be (6)
        conn.getNextElem should be (7)
    }

    it should "check if fork works" in {
        val conn = new TestConnection
        val stream = FStream(conn)

        conn.pushNext()
        conn.getNextElem should be (2)

        val res1 = stream.fork() <=> FConsumer{ stream => stream.read() }
        val res2 = stream.fork() <=> FConsumer{ stream => stream.read() }
        val res3 = stream.fork() <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res1.await() should be (2)
        res2.await() should be (2)
        res3.await() should be (2)

        val res = stream <=> FConsumer{ stream => stream.read() }
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

        val res1 = forkedForked <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res1.await() should be (1)

        val res2 = forked <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res2.await() should be (2)
    }

    it should "check if subscription logic works correctly" in {
        val conn = new TestConnection
        val stream = FStream(conn)
        val forkedStream = stream.fork()

        val res1 = stream <=> FConsumer{ stream => stream.read() }
        val fRes1 = forkedStream <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res1.await() should be (1)
        fRes1.await() should be (1)

        val res2 = stream <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res2.await() should be (2)

        val res3 = forkedStream <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res3.await() should be (3)

        val res4 = stream <=> FConsumer{ stream => stream.read() }
        val fRes4 = forkedStream <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res4.await() should be (4)
        fRes4.await() should be (4)
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

    it should "check if buffering functionality works" in {
        ???
    }
}
