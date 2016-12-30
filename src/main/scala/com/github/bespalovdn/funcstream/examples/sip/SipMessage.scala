package com.github.bespalovdn.funcstream.examples.sip

import javax.sip.header.CSeqHeader
import javax.sip.message.{Request, Response}

import com.github.bespalovdn.funcstream.examples.sip.internal.SipAccessPoint
import gov.nist.javax.sip.message.{SIPMessage, SIPRequest, SIPResponse}

trait SipMessage{
    def content: AnyRef = message.getContent

    def setContent(content: AnyRef, contentType: (String, String)): this.type = {
        message.setContent(content, sip.withHeaderFactory(_.createContentTypeHeader(contentType._1, contentType._2)))
        this
    }

    def cseq: Long = message.getHeader(CSeqHeader.NAME).asInstanceOf[CSeqHeader].getSeqNumber

    private [sip] def sip: SipAccessPoint
    private [sip] def message: SIPMessage
}

trait SipRequest extends SipMessage{
    override private[sip] def message: SIPRequest
}
trait SipResponse extends SipMessage{
    override private[sip] def message: SIPResponse
    def originRequest: SipRequest
}

object SipMessage
{
    def isInvite(r: SipRequest): Boolean = r.message.getMethod == Request.INVITE
    def isBye(r: SipRequest): Boolean = r.message.getMethod == Request.BYE
    def isOk(r: SipResponse): Boolean = r.message.getStatusCode == Response.OK
    def isTrying(r: SipResponse): Boolean = r.message.getStatusCode == Response.TRYING
}
