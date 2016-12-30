package com.github.bespalovdn.funcstream.examples.sipproxy

import com.github.bespalovdn.funcstream.FutureExtensions._
import com.github.bespalovdn.funcstream.examples.sip.SipMessage._
import com.github.bespalovdn.funcstream.examples.sip.{SipMessage, SipMessageFactory, SipRequest, SipResponse}
import com.github.bespalovdn.funcstream.impl.TimeoutSupport
import com.github.bespalovdn.funcstream.{FStream, _}

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.Success


trait HmpPart
{
    def sendInvite(sdp: String): Future[String]
    def sendBye(): Future[Unit]
    def waitForHmpBye: Future[Unit]
}

trait HmpPartFactory
{
    def createHmpPart(): Future[HmpPart]
}

class HmpPartImpl(client: ClientPart)(endpoint: FStream[SipMessage, SipMessage])
                 (implicit factory: SipMessageFactory)
    extends HmpPart with SipCommons with TimeoutSupport
{
    private val hmpByeReceived = Promise[Unit]
    private val done = Promise[Unit]

    override def sendInvite(sdp: String): Future[String] = for {
        hmpSdp <- endpoint <=> invite(sdp)
        _ <- fork(endpoint <=> {
            handleBye >>
                consumer(hmpByeReceived.tryComplete(Success(()))) >>
                consumer(done.tryComplete(Success(())))
        })
        _ <- {
            def canContinue = !done.future.isCompleted
            fork(endpoint <=> clientCSeqFilter <=> keepalive(canContinue))
        }
    } yield hmpSdp

    override def waitForHmpBye: Future[Unit] = hmpByeReceived.future

    override def sendBye(): Future[Unit] = endpoint <=> FConsumer{ implicit stream => for {
            _ <- stream.write(factory.byeRequest())
            _ <- stream.read() >>= {
                case r: SipResponse if isOk(r) => success(consume())
                case r => fail("hmp bye: invalid response received: " + r)
            }
            _ <- {done.tryComplete(Success(())); success()}
        } yield consume ()
    }

    def invite(sdp: String)(implicit factory: SipMessageFactory): SipConsumer[String] = FConsumer { implicit stream =>
        for {
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
    }

    def handleBye(implicit factory: SipMessageFactory): SipConsumer[Unit] = FConsumer { implicit stream =>
        for {
            r <- repeatOnFail(stream.read() >>= { case r: SipRequest if isBye(r) => success(r) })
            _ <- fork(client.sendBye())
            _ <- stream.write(factory.okResponse(r))
        } yield consume()
    }

    def keepalive(continue: => Boolean)(implicit factory: SipMessageFactory): ConstConsumer[SipResponse, SipRequest, Unit] =
    FConsumer { implicit stream =>
        for {
            _ <- waitFor(1.minute)
            _ <- stream.write(factory.keepaliveRequest(refresher = "uac"))
            _ <- stream.read(timeout = 10.seconds) >>= {
                case r: SipResponse if isOk(r) => success()
                case r => fail("Unexpected keepalive response: " + r)
            }
            _ <- if (continue) keepalive(continue)(factory).apply(stream) else success()
        } yield consume()
    }
}
