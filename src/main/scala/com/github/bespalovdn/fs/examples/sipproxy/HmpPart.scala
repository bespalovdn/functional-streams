package com.github.bespalovdn.fs.examples.sipproxy

import java.util.concurrent.atomic.AtomicReference

import com.github.bespalovdn.fs.FutureExtensions._
import com.github.bespalovdn.fs.examples.SipMessage._
import com.github.bespalovdn.fs.examples.{SipMessage, SipMessageFactory, SipRequest, SipResponse}
import com.github.bespalovdn.fs.{Stream, _}

import scala.concurrent.{Future, Promise}
import scala.util.Success


trait HmpPart
{
    def sendInvite(sdp: String): Future[String]
    def sendBye(): Future[Unit]
    def waitForHmpBye: Future[Unit]
}

class HmpPartImpl(client: ClientPart)(endpoint: Stream[SipMessage, SipMessage])
                 (implicit factory: SipMessageFactory)
    extends HmpPart with SipCommons
{
    private val byeReceived = Promise[Unit]

    override def sendInvite(sdp: String): Future[String] = for {
        hmpSdp <- endpoint <=> invite(sdp)
        _ <- fork(endpoint <=> {handleBye >> consumer(byeReceived.tryComplete(Success(())))})
        _ <- fork(endpoint <=> keepalive)
    } yield hmpSdp

    override def waitForHmpBye: Future[Unit] = byeReceived.future

    override def sendBye(): Future[Unit] = endpoint <=> { implicit stream => for {
            _ <- stream.write(factory.byeRequest())
            _ <- stream.read() >>= {
                case r: SipResponse if isOk(r) => success(consume())
                case r => fail("hmp bye: invalid response received: " + r)
            }
        } yield consume ()
    }

    def invite(sdp: String)(implicit factory: SipMessageFactory): Consumer[String] = implicit stream => for {
        _ <- stream.write(factory.inviteRequest(sdp))
        r <- stream.read() >>= {
            case r: SipResponse if isTrying(r) => stream.read()
            case r => success(r)
        }
        r <- r match {
            case r: SipResponse if isOk(r) => success(r)
            case _ => fail("invite: Unexpected response: " + r)
        }
        _ <- stream.write(factory.ackRequest(r))
    } yield consume(r.content.asInstanceOf[String])

    def handleBye(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => for {
        r <- repeatOnFail(stream.read() >>= {case r: SipRequest if isBye(r) => success(r)})
        _ <- fork(client.sendBye())
        _ <- stream.write(factory.okResponse(r))
    } yield consume()

    def keepalive(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => {
        ???
    }

    def clientCSeqFilter: Pipe[SipMessage, SipMessage, SipResponse, SipRequest] = upstream => {
        val lastCSeq = new AtomicReference[Option[Long]]()
        new Stream[SipResponse, SipRequest] {
            override def write(elem: SipRequest): Future[Unit] = {
                lastCSeq.set(Some(elem.cseq))
                upstream.write(elem)
            }
            override def read(): Future[SipResponse] = {
                val promise = Promise[SipResponse]
                //TODO: decide when to stop repeating, in case when consumer abandons the future provided:
                ???
                repeatOnFail {
                    upstream.read() >>= {
                        case r: SipResponse if lastCSeq.get().isEmpty => success(r)
                        case r: SipResponse if r.cseq == lastCSeq.get().get => success(r)
                    }
                } >>= { case r =>
                    promise.complete(Success(r))
                    success()
                }
                promise.future
            }
        }
    }
}
