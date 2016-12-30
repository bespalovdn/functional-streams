package com.github.bespalovdn.fs

import java.util.concurrent.TimeoutException

import com.github.bespalovdn.fs.impl.TimeoutSupport
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Failure

class FunctionalStreamsTest extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Inside
{
    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    "The test" should "check if read with timeout works" in {
        val endpoint = new Stream[Int, Int] with TimeoutSupport{
            override def read(timeout: Duration): Future[Int] = withTimeoutDo(timeout)(Promise[Int].future) // never completes
            override def write(elem: Int): Future[Unit] = Future.successful()
        }

        val consumer: ConstConsumer[Int, Int, Unit] = implicit stream => for {
            _ <- stream.read(timeout = 10.millisecond)
        } yield consume()

        val result: Future[Unit] = endpoint <=> consumer
        // check if it's not complete instantly:
        result.value should be (None)
        // wait for a while (more than timeout in consumer) and check if result completed with TimeoutException:
        Thread.sleep(100)
        inside (result.value) {
            case Some(Failure(t: Throwable)) => t shouldBe a [TimeoutException]
        }
    }
}
