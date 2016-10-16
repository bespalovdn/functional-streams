package com.github.bespalovdn.fs.examples

import com.github.bespalovdn.fs.Pipes._
import com.github.bespalovdn.fs.{PipeUtils, Pipes, Stream}

object SipSample extends App
{
    ???
}

trait SipSampleTypes
{
    // consumer that do not change the stream:
    type PureConsumer[A, B, C] = Pipes.Consumer[A, B, A, B, C]


    type Consumer[A] = PureConsumer[SipMessage, SipMessage, A]
}

trait SipMessage{
    def content: Any
    def setContent(a: Any): this.type
}

trait SipRequest extends SipMessage
trait SipResponse extends SipMessage

object SipMessage
{
    def isInvite(r: SipRequest): Boolean = ???
    def isTrying(r: SipResponse): Boolean = ???
    def isOk(r: SipResponse): Boolean = ???
    def isBye(r: SipRequest): Boolean = ???
}

trait SipMessageFactory
{
    def inviteRequest(sdp: String = null): SipRequest = ???
    def byeRequest(): SipRequest = ???
    def okResponse(request: SipRequest): SipResponse = ???
    def tryingResponse(request: SipRequest): SipResponse = ???
}

object SipClient extends SipSampleTypes with PipeUtils
{
    import SipMessage._

    import scala.concurrent.ExecutionContext.Implicits.global

    def sipEndpoint: Stream[SipMessage, SipMessage] = ???

    def invite(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => for {
        _ <- stream.write(factory.inviteRequest())
        r <- stream.read() >>= {
            case r: SipResponse if isTrying(r) => stream.read()
            case r => success(r)
        }
        _ <- r match {
            case r: SipResponse if isOk(r) => success()
            case _ => fail("invite: Unexpected response: " + r)
        }
    } yield consume()

    def bye(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream =>
        stream.write(factory.byeRequest()) >> stream.read() >>= {
            case r: SipResponse if isOk(r) => success(consume())
            case r => fail("bye: invalid response: " + r) //TODO: add retry logic
        }

    def waitForBye(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => stream.read() >>= {
        case r: SipRequest if isBye(r) => stream.write(factory.okResponse(r)) >> success(consume())
        case _ => waitForBye(factory)(stream)
    }

    class ByeReceivedException extends Exception

    def run(): Unit = {
        implicit val factory: SipMessageFactory = ???
        val stream = sipEndpoint
        val result = stream <*> {
            invite >>
            fork(waitForBye >> consumer(stream.close(new ByeReceivedException))) >>
            ???
        }
    }
}