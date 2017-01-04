package com.github.bespalovdn.funcstream.examples.sipproxy

import java.util.concurrent.atomic.AtomicReference

import com.github.bespalovdn.funcstream._
import com.github.bespalovdn.funcstream.examples.sip.{SipMessage, SipRequest, SipResponse}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}

trait SipCommons extends FutureExtensions
{
    type SipConsumer[A] = FPlainConsumer[SipMessage, SipMessage, A]
    type ClientSipConsumer[A] = FPlainConsumer[SipResponse, SipRequest, A]

    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

    def repeatOnFail[A](f: => Future[A]): Future[A] = f.recoverWith{case e: MatchError => repeatOnFail(f)}

    class CSeqChangedException extends Exception

    def clientCSeqFilter: FPipe[SipMessage, SipMessage, SipResponse, SipRequest] = (upstream: FStream[SipMessage, SipMessage]) => {
        new FStream[SipResponse, SipRequest] {
            val lastCSeq = new AtomicReference[Option[Long]]()
            override def write(elem: SipRequest): Future[Unit] = {
                lastCSeq.set(Some(elem.cseq))
                upstream.write(elem)
            }
            override def read(timeout: Duration): Future[SipResponse] = {
                val promise = Promise[SipResponse]
                val currCSeq = lastCSeq.get()
                val f = repeatOnFail {
                    if(lastCSeq.get() != currCSeq)
                        throw new CSeqChangedException()
                    upstream.read(timeout) >>= {
                        case r: SipResponse if lastCSeq.get().isEmpty => success(r)
                        case r: SipResponse if r.cseq == lastCSeq.get().get => success(r)
                    }
                }
                f.onComplete(promise.complete)
                promise.future
            }
        }
    }
}
