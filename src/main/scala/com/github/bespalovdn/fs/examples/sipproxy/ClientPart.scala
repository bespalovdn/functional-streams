package com.github.bespalovdn.fs.examples.sipproxy

import com.github.bespalovdn.fs.FutureExtensions._
import com.github.bespalovdn.fs._
import com.github.bespalovdn.fs.examples.sip.SipMessage._
import com.github.bespalovdn.fs.examples.sip.{SipMessage, SipMessageFactory, SipRequest, SipResponse}

import scala.concurrent.Future

trait ClientPart
{
    def sendBye(): Future[Unit]
}

class ClientPartImpl(endpoint: Stream[SipMessage, SipMessage])
    (implicit factory: SipMessageFactory)
    extends ClientPart with SipCommons
{
    override def sendBye(): Future[Unit] = ???

    def createHmpPart(): Future[HmpPart] = ???

    def clientHandler(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => for {
        r <- stream.read() >>= {
            case r: SipRequest if isInvite(r) => success(r)
            case r => fail("Unexpected request received: " + r)
        }
        _ <- stream.write(factory.tryingResponse(r))
        sdp <- success(r.content.asInstanceOf[String])
        hmp <- createHmpPart()
        hmpSdp <- hmp.sendInvite(sdp)
        _ <- stream.write(factory.okResponse(r).setContent(hmpSdp, "application" -> "sdp"))
        _ <- (stream <=> handleBye(hmp)) <|> (stream <=> handleHmpBye(hmp))
    } yield consume()

    def handleBye(hmp: HmpPart)(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => for {
        r <- repeatOnFail(stream.read() >>= {case r: SipRequest if isBye(r) => success(r)})
        _ <- hmp.sendBye()
        _ <- stream.write(factory.okResponse(r))
    } yield consume()

    def handleHmpBye(hmp: HmpPart)(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => for {
        _ <- hmp.waitForHmpBye
        _ <- stream.write(factory.byeRequest()) >> repeatOnFail{
            stream.read() >>= {case r: SipResponse if isOk(r) => success(r)}
        }
    } yield consume()

    def run(): Unit ={
        endpoint <=> clientHandler
    }
}