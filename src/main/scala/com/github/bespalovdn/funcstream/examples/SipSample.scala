package com.github.bespalovdn.funcstream.examples

import com.github.bespalovdn.funcstream.examples.sip.SipMessage._
import com.github.bespalovdn.funcstream.examples.sip._
import com.github.bespalovdn.funcstream.{FStream, _}

object SipSample extends App
{
    ???
}

trait SipSampleTypes
{
    type Consumer[A] = FPlainConsumer[SipMessage, SipMessage, A]
}

object SipClient extends SipSampleTypes with FutureExtensions
{

    import scala.concurrent.ExecutionContext.Implicits.global

    def sipEndpoint: FStream[SipMessage, SipMessage] = ???

    def invite(implicit factory: SipMessageFactory): Consumer[Unit] = FConsumer { implicit stream => for {
            _ <- stream.write(factory.inviteRequest())
            r <- stream.read() >>= {
                case r: SipResponse if isTrying(r) => stream.read()
                case r => success(r)
            }
            _ <- r match {
                case r: SipResponse if isOk(r) => success()
                case _ => fail(new SipProtocolException("invite: Unexpected response: " + r))
            }
        } yield consume()
    }

    def bye(implicit factory: SipMessageFactory): Consumer[Unit] = FConsumer { implicit stream =>
        stream.write(factory.byeRequest()) >> stream.read() >>= {
            case r: SipResponse if isOk(r) => success(consume())
            case r => fail(new SipProtocolException("bye: invalid response: " + r)) //TODO: add retry logic
        }
    }

    def waitForBye(implicit factory: SipMessageFactory): Consumer[Unit] = FConsumer { implicit stream =>
        stream.read() >>= {
            case r: SipRequest if isBye(r) => stream.write(factory.okResponse(r)) >> success(consume())
            case _ => waitForBye(factory).apply(stream)
        }
    }

    class ByeReceivedException extends Exception

    def run(): Unit = {
        implicit val factory: SipMessageFactory = ???
        val stream = sipEndpoint
        val result = stream <=> {
            invite >>
//            fork(waitForBye >> consumer(??? : Unit /*to stop processing*/)) >>
            ???
        }
    }
}