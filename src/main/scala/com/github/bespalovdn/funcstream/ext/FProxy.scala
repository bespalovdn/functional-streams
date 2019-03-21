package com.github.bespalovdn.funcstream.ext

import com.github.bespalovdn.funcstream.{FConsumer, FStream}

import scala.concurrent.{ExecutionContext, Future, Promise}


class FProxy[A, B, C] extends (FStream[A, B] => Future[C])
{
    private var p: Promise[C] = null
    private var s: FStream[A, B] = null

    override def apply(stream: FStream[A, B]): Future[C] = {
        s = stream
        p = Promise[C]
        p.future
    }

    def <=> (consumer: FConsumer[A, B, C])(implicit ec: ExecutionContext): Future[C] = {
        consumer.consume(s) andThen {
            case c => p.tryComplete(c)
        }
    }
}
