package com.github.bespalovdn.funcstream.examples.sipproxy

import com.github.bespalovdn.funcstream._
import com.github.bespalovdn.funcstream.examples.sip.SipMessage._
import com.github.bespalovdn.funcstream.examples.sip.{SipMessage, SipMessageFactory, SipRequest, SipResponse}

import scala.concurrent.Future

trait ClientPart
{
    def run(): Future[Unit]
    def sendBye(): Future[Unit]
}

trait ClientPartFactory
{
    def createClientPart(): Future[ClientPart]
}

class ClientPartImpl(endpoint: FStream[SipMessage, SipMessage], hmpPartFactory: HmpPartFactory)
    (implicit factory: SipMessageFactory)
    extends ClientPart with SipCommons with FutureExtensions
{
    def run(): Future[Unit] ={
        endpoint <=> clientHandler
    }

    override def sendBye(): Future[Unit] = {
        val consumer: ClientSipConsumer[Unit] = FConsumer { implicit stream =>
            for {
                _ <- stream.write(factory.byeRequest())
                _ <- stream.read() >>= {
                    case r: SipResponse if isOk(r) => success(consume())
                    case r => fail("hmp bye: invalid response received: " + r)
                }
            } yield consume()
        }
        endpoint <=> clientCSeqFilter <=> consumer
    }

    def clientHandler(implicit factory: SipMessageFactory): SipConsumer[Unit] = FConsumer { implicit stream =>
        for {
            r <- stream.read() >>= {
                case r: SipRequest if isInvite(r) => success(r)
                case r => fail("Unexpected request received: " + r)
            }
            _ <- stream.write(factory.tryingResponse(r))
            sdp <- success(r.content.asInstanceOf[String])
            hmp <- hmpPartFactory.createHmpPart()
            hmpSdp <- hmp.sendInvite(sdp)
            _ <- stream.write(factory.okResponse(r).setContent(hmpSdp, "application" -> "sdp"))
            _ <- (stream <=> handleBye(hmp)) <|> (stream <=> clientCSeqFilter <=> handleHmpBye(hmp))
        } yield consume()
    }

    def handleBye(hmp: HmpPart)(implicit factory: SipMessageFactory): SipConsumer[Unit] = FConsumer { implicit stream =>
        for {
            r <- repeatOnFail(stream.read() >>= { case r: SipRequest if isBye(r) => success(r) })
            _ <- hmp.sendBye()
            _ <- stream.write(factory.okResponse(r))
        } yield consume()
    }

    def handleHmpBye(hmp: HmpPart)(implicit factory: SipMessageFactory): ClientSipConsumer[Unit] = FConsumer { implicit stream =>
        for {
            _ <- hmp.waitForHmpBye
            _ <- stream.write(factory.byeRequest()) >> repeatOnFail {
                stream.read() >>= { case r: SipResponse if isOk(r) => success(r) }
            }
        } yield consume()
    }
}