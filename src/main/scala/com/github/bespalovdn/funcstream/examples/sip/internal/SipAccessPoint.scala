package com.github.bespalovdn.funcstream.examples.sip.internal

import java.net.InetSocketAddress
import javax.sip.Dialog
import javax.sip.address.AddressFactory
import javax.sip.header.CallIdHeader
import javax.sip.message.MessageFactory

import gov.nist.javax.sip.header.HeaderFactoryImpl

trait SipAccessPoint
{
    def localBindAddr: InetSocketAddress
    def targetConnectAddr: InetSocketAddress

    def withAddressFactory[A](fn: AddressFactory => A): A
    def withHeaderFactory[A](fn: HeaderFactoryImpl => A): A
    def withMessageFactory[A](fn: MessageFactory => A): A

//    def newClientTransaction(request: Request): ClientTransaction
//    def newServerTransaction(request: Request): ServerTransaction
//
//    def newClientDialog(firstRequest: SIPRequest): Dialog
//    def newServerDialog(firstRequest: SIPRequest): Dialog

    def newCallId(): CallIdHeader

    def dialog: Dialog
}
