package com.github.bespalovdn.fs.examples.sipproxy

import com.github.bespalovdn.fs
import com.github.bespalovdn.fs.FutureExtensions._
import com.github.bespalovdn.fs.examples.SipMessage._
import com.github.bespalovdn.fs.examples.{SipMessage, SipMessageFactory, SipRequest, SipResponse}
import com.github.bespalovdn.fs.{Stream, _}

import scala.concurrent.{ExecutionContext, Future}

object SipProxy extends App {
    ???
}

trait SipCommons
{
    type ConstConsumer[A, B, C] = fs.Consumer[A, B, A, B, C]
    type Consumer[A] = ConstConsumer[SipMessage, SipMessage, A]

    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

    def repeatOnFail[A](f: => Future[A]): Future[A] = f.recoverWith{case e: MatchError => repeatOnFail(f)}
}

trait ClientPart
{
    def sendBye(): Future[Unit]
}

trait ClientPartImpl extends SipCommons
{
    def clientEndpoint: Stream[SipMessage, SipMessage] = ???
    implicit def factory: SipMessageFactory = ???
    def hmpEndpoint: Stream[SipMessage, SipMessage] = ???

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
        _ <- stream.write(factory.okResponse(r).setContent(hmpSdp))
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

    clientEndpoint <=> clientHandler
}