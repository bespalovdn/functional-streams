package com.github.bespalovdn.fs.examples.sipproxy

import java.util.concurrent.atomic.AtomicReference

import com.github.bespalovdn.fs._
import com.github.bespalovdn.fs.examples.sip.{SipRequest, SipResponse, SipMessage}
import com.github.bespalovdn.fs.FutureExtensions._

import scala.concurrent.duration.Duration
import scala.concurrent.{Promise, Future, ExecutionContext}

trait SipCommons
{
    type SipConsumer[A] = ConstConsumer[SipMessage, SipMessage, A]
    type ClientSipConsumer[A] = ConstConsumer[SipResponse, SipRequest, A]

    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

    def repeatOnFail[A](f: => Future[A]): Future[A] = f.recoverWith{case e: MatchError => repeatOnFail(f)}

    class CSeqChangedException extends Exception

    def clientCSeqFilter: Pipe[SipMessage, SipMessage, SipResponse, SipRequest] = upstream => {
        new Stream[SipResponse, SipRequest] {
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
