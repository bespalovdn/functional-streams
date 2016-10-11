package com.github.bespalovdn.fs.examples

import com.github.bespalovdn.fs.{PipeUtils, Pipes}

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

trait SipMessage

trait SipRequest extends SipMessage
trait SipResponse extends SipMessage

object SipMessage
{
    def isTrying(r: SipResponse): Boolean = ???
    def isOk(r: SipResponse): Boolean = ???
    def isBye(r: SipRequest): Boolean = ???
}

trait SipMessageFactory
{
    def inviteRequest(sdp: String = null): SipRequest = ???
}

trait SipClient extends SipSampleTypes with PipeUtils
{
    import SipMessage._

    import scala.concurrent.ExecutionContext.Implicits.global

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

    final def bye(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => stream.read() >>= {
        case r: SipRequest if isBye(r) => success(consume())
        case _ => bye(factory)(stream)
    }

}