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
}

trait SipMessageFactory
{
    def createInvite(sdp: String = null): SipRequest = ???
}

trait SipClient extends SipSampleTypes with PipeUtils
{
    import SipMessage._

    def invite(implicit factory: SipMessageFactory): Consumer[Unit] = implicit stream => for {
        _ <- stream.write(factory.createInvite())
        r <- stream.read() >>= {
            case r: SipResponse if isTrying(r) => stream.read()
            case r => success(r)
        }
        _ <- r match {
            case r: SipResponse if isOk(r) => success()
            case _ => fail("Unexpected response: " + r)
        }
    } yield consume()

}