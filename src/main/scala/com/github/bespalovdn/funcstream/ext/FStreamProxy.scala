package com.github.bespalovdn.funcstream.ext

import com.github.bespalovdn.funcstream.{FConsumer, FStream}

import scala.concurrent.{Future, Promise}


trait FStreamProvider[A, B, C]{
    def <=> (consumer: FConsumer[A, B, C]): Future[C]
}

trait FStreamConsumer[A, B, C]{
    def consume(stream: FStream[A, B]): Future[C]
}

class FStreamProxy[A, B, C] extends FStreamConsumer[A, B, C] with FStreamProvider[A, B, C]
{
    private val p = Promise[C]
    private var s: FStream[A, B] = null
    override def consume(stream: FStream[A, B]): Future[C] = {
        s = stream
        p.future
    }
    override def <=> (consumer: FConsumer[A, B, C]): Future[C] = {
        consumer.consume(s) andThen {
            case c => p.tryComplete(c)
        }
    }
}
