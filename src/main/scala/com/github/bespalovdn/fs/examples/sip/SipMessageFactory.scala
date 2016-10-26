package com.github.bespalovdn.fs.examples.sip

trait SipMessageFactory
{
    def ackRequest(response: SipResponse): SipRequest = ???
    def byeRequest(): SipRequest = ???
    def keepaliveRequest(): SipRequest = ???
    def inviteRequest(sdp: String = null): SipRequest = ???

    def okResponse(request: SipRequest): SipResponse = ???
    def tryingResponse(request: SipRequest): SipResponse = ???
}

