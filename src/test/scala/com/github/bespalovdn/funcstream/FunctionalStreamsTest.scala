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
class FunctionalStreamsTest extends FlatSpec
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

    it should "check if stream consumes input from producer when subscribed only" in {
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

        //TODO: to be continued
    }

    it should "check if forking works" in {
        val conn = new TestConnection
        val stream = FStream(conn)

        conn.pushNext()
        conn.getNextElem should be (2)

        val res1 = stream <=> FConsumer{ stream => stream.read() }
        val res2 = stream <=> FConsumer{ stream => stream.read() }
        conn.pushNext()
        res1.await() should be (2)
        res2.await() should be (2)
    }

//    it should "check if piping functionality works" in {
//        val connection = new FStreamV1[Int, Int] {
//            var lastWrittenElem: Int = 0
//            override def read(timeout: Duration): Future[Int] = success(lastWrittenElem)
//            override def write(elem: Int): Future[Unit] = {
//                lastWrittenElem = elem
//                success()
//            }
//        }
//        val twice: FPipe[Int, Int, Int, Int] = FPipe{ upStream => new FStreamV1[Int, Int]{
//            override def read(timeout: Duration): Future[Int] = upStream.read(timeout).map(_ * 2)
//            override def write(elem: Int): Future[Unit] = upStream.write(elem * 2)
//        }}
//        val consumer: FPlainConsumer[Int, Int, Unit] = FConsumerV1{ implicit stream => for {
//                i <- stream.read()
//                _ <- {i should be (0); success()}
//                _ <- stream.write(1)
//                _ <- {connection.lastWrittenElem should be (2); success()}
//                i <- stream.read()
//                _ <- {i should be (4); success()}
//            } yield consume()
//        }
//        FStreamConnector(connection) <=> twice <=> consumer
//    }

//    it should "check if stream forking works" in {
//        val connection = new FStreamV1[Int, Int] {
//            private var _readCount: Int = 0
//            private val readValue: Iterator[Int] = Stream.from(1).iterator
//
//            override def read(timeout: Duration): Future[Int] = {
//                _readCount += 1
//                success(readValue.next())
//            }
//            override def write(elem: Int): Future[Unit] = success(())
//
//            def readCount: Int = _readCount
//        }
//
//        type Consumer = FPlainConsumer[Int, Int, Unit]
//
//        val echoServer: Consumer = FConsumerV1 { implicit stream => for {
//                a <- stream.read()
//                _ <- stream.write(a)
//            } yield consume ()
//        }
//
//        def check(fn: => Unit): Consumer = FConsumerV1{ implicit stream =>
//            fn
//            success(consume())
//        }
//
//        FStreamConnector(connection) <=> {
//            check{
//                connection.readCount should be (0)
//            } >> echoServer >> check {
//                connection.readCount should be (1)
//            } >> FConsumerV1.fork(echoServer) >> echoServer >> check {
//                connection.readCount should be (3)
//            }
//        }
//    }

    //TODO: add more tests with stream forking (consumer + pipe)
}
