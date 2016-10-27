package com.github.bespalovdn.fs.examples.sip

import java.util
import java.util.UUID
import javax.sip.address.{Address, SipURI}
import javax.sip.header._
import javax.sip.message.{Response, Request}

import com.github.bespalovdn.fs.examples.sip.internal.SipAccessPoint
import com.ringcentral.rcv.hsc.hmp.HmpSipHeader
import gov.nist.javax.sip.SIPConstants
import gov.nist.javax.sip.message.{SIPRequest, SIPResponse}

import scala.collection.JavaConverters._

trait SipMessageFactory
{
    def ackRequest(response: SipResponse): SipRequest
    def byeRequest(): SipRequest
    def keepaliveRequest(): SipRequest
    def inviteRequest(sdp: String = null): SipRequest

    def okResponse(request: SipRequest): SipResponse
    def tryingResponse(request: SipRequest): SipResponse
}

object SipMessageFactory
{
    private class SipRequestImpl(val message: SIPRequest, val sip: SipAccessPoint) extends SipRequest
    private class SipResponseImpl(val message: SIPResponse, val sip: SipAccessPoint) extends SipResponse

    def create(sip: SipAccessPoint): SipMessageFactory = new SipMessageFactory {
        override def ackRequest(response: SipResponse): SipRequest = {
            val msg = sip.dialog.createAck(response.cseq).asInstanceOf[SIPRequest]
            new SipRequestImpl(msg, sip)
        }

        override def byeRequest(): SipRequest = {
            val msg = sip.dialog.createRequest(Request.BYE).asInstanceOf[SIPRequest]
            new SipRequestImpl(msg, sip)
        }

        override def keepaliveRequest(): SipRequest = {
            val msg = sip.dialog.createRequest(Request.UPDATE).asInstanceOf[SIPRequest]
            val sessionExpiresHeader = sip.withHeaderFactory(_.createSessionExpiresHeader(SipConstants.SESSION_EXPIRES))
            sessionExpiresHeader.setRefresher("uac")
            msg.setHeader(sessionExpiresHeader)
            val minSEHeader = sip.withHeaderFactory(_.createMinSEHeader(SipConstants.MIN_SE))
            msg.setHeader(minSEHeader)
            new SipRequestImpl(msg, sip)
        }

        override def inviteRequest(sdp: String): SipRequest = {
            val hmpHost = sip.targetConnectAddr.getHostString
            val hmpPort = sip.targetConnectAddr.getPort

            // create From Header
            val nameFrom: String = "HSC"
            val hostFrom: String = sip.localBindAddr.getHostString
            val displayNameFrom: String = nameFrom
            val fromAddress: SipURI = sip.withAddressFactory(_.createSipURI(nameFrom, hostFrom))
            val fromNameAddress: Address = sip.withAddressFactory(_.createAddress(fromAddress))
            fromNameAddress.setDisplayName(displayNameFrom)
            val fromTag = UUID.randomUUID().toString
            val fromHeader: FromHeader = sip.withHeaderFactory(_.createFromHeader(fromNameAddress, fromTag))

            // create To Header
            val nameTo: String = "msml"
            val displayNameTo: String = "msml"
            val hostTo: String = hmpHost
            val toAddress: SipURI = sip.withAddressFactory(_.createSipURI(nameTo, hostTo))
            val toNameAddress: Address = sip.withAddressFactory(_.createAddress(toAddress))
            toNameAddress.setDisplayName(displayNameTo)
            val toHeader: ToHeader = sip.withHeaderFactory(_.createToHeader(toNameAddress, null))

            // Create Via header
            val transport = "UDP"
            val viaHeaders = new util.ArrayList[ViaHeader]
            val branch = SIPConstants.BRANCH_MAGIC_COOKIE + "-" + UUID.randomUUID().toString
            val viaHeader: ViaHeader = sip.withHeaderFactory(
                _.createViaHeader(sip.localBindAddr.getHostString, sip.localBindAddr.getPort, transport, branch))
            viaHeaders.add(viaHeader)

            // Create Max-Forwards header:
            val maxForwards: MaxForwardsHeader = sip.withHeaderFactory(_.createMaxForwardsHeader(SipConstants.MAX_FORWARDS))

            // Create a new CallId header
            val callIdHeader: CallIdHeader = sip.newCallId()

            // Create a new Cseq header
            val cSeqHeader: CSeqHeader = sip.withHeaderFactory(_.createCSeqHeader(1L, Request.INVITE))

            // Create a new MaxForwardsHeader

            // Create Request URI
            val peerLocation: String = hmpHost + ":" + hmpPort
            val requestURI: SipURI = sip.withAddressFactory(_.createSipURI(nameTo, peerLocation))

            // Create Request:
            val request: SIPRequest = sip.withMessageFactory(_.createRequest(
                requestURI, Request.INVITE, callIdHeader, cSeqHeader, fromHeader, toHeader, viaHeaders, maxForwards)).
                asInstanceOf[SIPRequest]

            // Create contact headers
            // Create the contact name address.
            val contactURI: SipURI = sip.withAddressFactory(_.createSipURI(nameFrom, sip.localBindAddr.getHostString))
            contactURI.setPort(sip.localBindAddr.getPort)

            // Add the contact address.
            val contactAddress: Address = sip.withAddressFactory(_.createAddress(contactURI))
            contactAddress.setDisplayName(nameFrom)
            val contactHeader: ContactHeader = sip.withHeaderFactory(_.createContactHeader(contactAddress))
            request.addHeader(contactHeader)

            // Add ALLOW headers:
            val allowHeader = sip.withHeaderFactory(_.createAllowHeader(SipConstants.ALLOW_COMMANDS))
            request.addHeader(allowHeader)

            // Add User-Agent header:
            val userAgentHeader = sip.withHeaderFactory(_.createUserAgentHeader(List(SipConstants.USER_AGENT).asJava))
            request.addHeader(userAgentHeader)

            // Add Session-Expires header:
            val sessionExpiresHeader = sip.withHeaderFactory(_.createSessionExpiresHeader(SipConstants.SESSION_EXPIRES))
            sessionExpiresHeader.setRefresher("uac")
            request.setHeader(sessionExpiresHeader)
            val minSEHeader = sip.withHeaderFactory(_.createMinSEHeader(SipConstants.MIN_SE))
            request.setHeader(minSEHeader)

            new SipRequestImpl(request, sip)
        }

        override def okResponse(request: SipRequest): SipResponse = {
            val msg = request.asInstanceOf[SIPRequest].createResponse(Response.OK)
            new SipResponseImpl(msg, sip)
        }

        override def tryingResponse(request: SipRequest): SipResponse = {
            val msg = request.message.createResponse(Response.TRYING)
            new SipResponseImpl(msg, sip)
        }
    }
}

object SipConstants
{
    def MAX_FORWARDS = 70
    def ALLOW_COMMANDS: String = "ACK, BYE, CANCEL, INFO, INVITE, NOTIFY, REFER, SUBSCRIBE, UPDATE"
    def USER_AGENT = "RC_HSC_1.0"
    def SESSION_EXPIRES = 1800
    def MIN_SE = 90
}