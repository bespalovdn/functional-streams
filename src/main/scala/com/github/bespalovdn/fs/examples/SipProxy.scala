package com.github.bespalovdn.fs.examples

import com.github.bespalovdn.fs.Pipes._
import com.github.bespalovdn.fs.examples.SipMessage._
import com.github.bespalovdn.fs.{PipeUtils, Pipes, Stream}

import scala.concurrent.{ExecutionContext, Future}

object SipProxy extends App {
    ???
}

trait SipProxyCommons
{
    // consumer that do not change the stream:
    type PureConsumer[A, B, C] = Pipes.Consumer[A, B, A, B, C]
    type Consumer[A] = PureConsumer[SipMessage, SipMessage, A]

    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
}

trait HmpPart extends SipProxyCommons with PipeUtils
{
    def sendInvite(sdp: String): Future[String]
    def sendBye(): Future[Unit]
    def waitForHmpBye: Future[Unit]
}

trait SipProxyIn extends SipProxyCommons with PipeUtils
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
        hmp <- createHmpPart()
        sdp <- success(r.content.asInstanceOf[String])
        hmpSdp <- hmp.sendInvite(sdp)
        _ <- stream.write(factory.okResponse(r).setContent(hmpSdp))
        hmpBye <- fork(handleHmpBye(hmp))(stream).map(_.value)
        _ <- handleBye(hmp)(factory)(stream).map(_.value) <|> hmpBye
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

    clientEndpoint <*> clientHandler
}