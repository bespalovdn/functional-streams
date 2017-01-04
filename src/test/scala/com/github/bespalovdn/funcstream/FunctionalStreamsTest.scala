package com.github.bespalovdn.funcstream

import java.util.concurrent.TimeoutException

import com.github.bespalovdn.funcstream.impl.TimeoutSupport
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Failure

@RunWith(classOf[JUnitRunner])
class FunctionalStreamsTest extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Inside
{
    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    "The test" should "check if read with timeout works" in {
        val endpoint = new FStream[Int, Int] with TimeoutSupport{
            override def read(timeout: Duration): Future[Int] = withTimeoutDo(timeout)(Promise[Int].future) // never completes
            override def write(elem: Int): Future[Unit] = Future.successful(())
        }

        val consumer: ConstConsumer[Int, Int, Unit] = FConsumer { implicit stream => for {
                _ <- stream.read(timeout = 10.millisecond)
            } yield consume()
        }

        val result: Future[Unit] = endpoint <=> consumer
        // check if it's not complete instantly:
        result.value should be (None)
        // wait for a while (more than timeout in consumer) and check if result completed with TimeoutException:
        Thread.sleep(100)
        inside (result.value) {
            case Some(Failure(t: Throwable)) => t shouldBe a [TimeoutException]
        }
    }

    it should "check if stream forking works" in {
        val endpoint = new FStream[Int, Int] {
            private var _readCount: Int = 0
            private val readValue: Iterator[Int] = Stream.from(1).iterator

            override def read(timeout: Duration): Future[Int] = {
                _readCount += 1
                success(readValue.next())
            }
            override def write(elem: Int): Future[Unit] = success(())

            def readCount: Int = _readCount
        }

        type Consumer = ConstConsumer[Int, Int, Unit]

        val echoServer: Consumer = FConsumer { implicit stream => for {
                a <- stream.read()
                _ <- stream.write(a)
            } yield consume ()
        }

        def check(fn: => Unit): Consumer = FConsumer{ implicit stream =>
            fn
            success(consume())
        }

        endpoint <=> {
            check{
                endpoint.readCount should be (0)
            } >> echoServer >> check {
                endpoint.readCount should be (1)
            }
        }
    }
}
