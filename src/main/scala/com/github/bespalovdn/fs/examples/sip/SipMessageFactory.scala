package com.github.bespalovdn.fs.examples.sip

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
    def create(): SipMessageFactory = new SipMessageFactory {
        override def ackRequest(response: SipResponse): SipRequest = ???
        override def inviteRequest(sdp: String): SipRequest = ???
        override def byeRequest(): SipRequest = ???
        override def keepaliveRequest(): SipRequest = ???
        override def okResponse(request: SipRequest): SipResponse = ???
        override def tryingResponse(request: SipRequest): SipResponse = ???
    }
}