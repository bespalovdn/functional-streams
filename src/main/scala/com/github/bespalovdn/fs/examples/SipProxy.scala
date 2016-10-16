package com.github.bespalovdn.fs.examples

import com.github.bespalovdn.fs.{PipeUtils, Pipes, Stream}
import com.github.bespalovdn.fs.Pipes._

import scala.concurrent.{Future, ExecutionContext}

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

trait HmpPart
{
    def sendInvite(sdp: String): Future[String]
    def sendBye(): Future[Unit]
}

trait SipProxyIn extends SipProxyCommons with PipeUtils
{
    import SipMessage._

    def clientEndpoint: Stream[SipMessage, SipMessage] = ???
    implicit def factory: SipMessageFactory = ???
    def hmpEndpoint: Stream[SipMessage, SipMessage] = ???

    def oneOf[A, B](pf: PartialFunction[A, Future[B]])(a: A): Future[B] = {
        val f: PartialFunction[A, Future[B]] = pf orElse {case msg => fail("Unexpected message received: " + msg)}
        f(a)
    }

    def handleInvite(hmp: HmpPart)(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => for {
        r <- stream.read() >>= oneOf{case r: SipRequest if isInvite(r) => success(r)}
        _ <- stream.write(factory.tryingResponse(r))
        sdp <- success(r.content.asInstanceOf[String])
        hmpSdp <- hmp.sendInvite(sdp)
        _ <- stream.write(factory.okResponse(r).setContent(hmpSdp))
    } yield consume()

    def repeatOnFail[A](f: => Future[A]): Future[A] = f.recoverWith{case _ => repeatOnFail(f)}

    def handleBye(hmp: HmpPart)(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => for {
        r <- repeatOnFail(stream.read() >>= {case r: SipRequest if isBye(r) => success(r)})
        _ <- hmp.sendBye()
        _ <- stream.write(factory.okResponse(r))
    } yield consume()

    val hmp: HmpPart = ???

    clientEndpoint <*> {
        handleInvite(hmp) >>
        handleBye(hmp)
    }

}