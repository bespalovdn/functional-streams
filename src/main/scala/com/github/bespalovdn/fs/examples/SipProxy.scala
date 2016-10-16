package com.github.bespalovdn.fs.examples

import com.github.bespalovdn.fs.{PipeUtils, Pipes, Stream}
import com.github.bespalovdn.fs.Pipes._

import scala.concurrent.{Future, ExecutionContext}

object SipProxy extends App {

}

trait SipProxyCommons
{
    // consumer that do not change the stream:
    type PureConsumer[A, B, C] = Pipes.Consumer[A, B, A, B, C]
    type Consumer[A] = PureConsumer[SipMessage, SipMessage, A]

    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
}

trait SipProxy extends SipProxyCommons with PipeUtils
{
    import SipMessage._

    def clientEndpoint: Stream[SipMessage, SipMessage] = ???
    implicit def factory: SipMessageFactory = ???
    def hmpEndpoint: Stream[SipMessage, SipMessage] = ???

    def oneOf[A, B](pf: PartialFunction[A, Future[B]])(a: A): Future[B] = {
        val f: PartialFunction[A, Future[B]] = pf orElse {case msg => fail("Unexpected message received: " + msg)}
        f(a)
    }

    def waitForInvite(implicit factory: SipMessageFactory): Consumer[String] = implicit stream => for {
        r <- stream.read() >>= oneOf{case r: SipRequest if isInvite(r) => success(r)}
        _ <- stream.write(factory.tryingResponse(r))
        sdp <- success(r.content.asInstanceOf[String])
    } yield consume(sdp)

    clientEndpoint <*> {
        waitForInvite >>
        (??? : Consumer[_])
    }

}