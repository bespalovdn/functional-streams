package com.github.bespalovdn.funcstream

import com.github.bespalovdn.funcstream.exception.ConnectionClosedException
import com.github.bespalovdn.funcstream.ext.FutureUtils._
import com.github.bespalovdn.funcstream.test.DefaultPublisher
import org.scalatest._

import scala.concurrent.Future
import scala.util.Success


trait UT extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Inside

class TestPublisher extends DefaultPublisher[Int] {
    private var nextElem: Int = 1
    def pushNext(): Unit = {
        forEachSubscriber(s => s.push(Success(nextElem)))
        nextElem += 1
    }
    def getNextElem: Int = nextElem
}

class TestConnection extends TestPublisher with Connection[Int, Int]{
    override def write(elem: Int): Future[Unit] =
        if(closed.isCompleted) fail(new ConnectionClosedException)
        else success()
}