package com.github.bespalovdn.fs.examples.sip

trait SipMessage{
    def content: Any
    def setContent(a: Any): this.type

    def cseq: Long
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
