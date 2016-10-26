package com.github.bespalovdn.fs.examples.sip.internal

import java.net.InetSocketAddress
import javax.sip.address.AddressFactory
import javax.sip.header.CallIdHeader
import javax.sip.message.{MessageFactory, Request}
import javax.sip.{ClientTransaction, Dialog, ServerTransaction}

import gov.nist.javax.sip.header.HeaderFactoryImpl
import gov.nist.javax.sip.message.SIPRequest

trait SipAccessPoint
{
    def localBindAddr: InetSocketAddress

    def withAddressFactory[A](fn: AddressFactory => A): A
    def withHeaderFactory[A](fn: HeaderFactoryImpl => A): A
    def withMessageFactory[A](fn: MessageFactory => A): A

    def newClientTransaction(request: Request): ClientTransaction
    def newServerTransaction(request: Request): ServerTransaction

    def newClientDialog(firstRequest: SIPRequest): Dialog
    def newServerDialog(firstRequest: SIPRequest): Dialog

    def newCallId(): CallIdHeader
}
