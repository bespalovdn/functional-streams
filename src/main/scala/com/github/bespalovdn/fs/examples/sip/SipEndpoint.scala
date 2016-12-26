package com.github.bespalovdn.fs.examples.sip

import java.net.InetSocketAddress
import java.util.Properties
import javax.sip._
import javax.sip.address.AddressFactory
import javax.sip.message.MessageFactory

import com.github.bespalovdn.fs.Stream
import gov.nist.javax.sip.header.HeaderFactoryImpl
import gov.nist.javax.sip.message.SIPRequest

import scala.concurrent.Future
import scala.concurrent.duration.Duration

class SipEndpoint(val bindAddr: InetSocketAddress)
    extends Stream[SipMessage, SipMessage]
        with SipStackImpl
        with SipListener
{
    override def read(timeout: Duration): Future[SipMessage] = ???

    override def write(msg: SipMessage): Future[Unit] = {
        msg match {
            case r: SipRequest =>
                r.message.getTransaction.asInstanceOf[ClientTransaction].sendRequest()
            case r: SipResponse =>
                val transaction = r.originRequest.message.getTransaction.asInstanceOf[ServerTransaction]
                transaction.sendResponse(r.message)
        }
        Future.successful(())
    }

    override def processRequest(event: RequestEvent): Unit = {
        val request = event.getRequest.asInstanceOf[SIPRequest]
        ???
    }

    override def processResponse(responseEvent: ResponseEvent): Unit = ???

    override def processIOException(ioExceptionEvent: IOExceptionEvent): Unit = ???

    override def processTimeout(timeoutEvent: TimeoutEvent): Unit = ???

    override def processTransactionTerminated(transactionTerminatedEvent: TransactionTerminatedEvent): Unit = ???

    override def processDialogTerminated(dialogTerminatedEvent: DialogTerminatedEvent): Unit = ???
}

trait SipStackImpl extends SipStackProperties
{
    this: SipListener =>

    def bindAddr: InetSocketAddress

    var sipStack: SipStack = null
    var sipListeningPoint: ListeningPoint = null
    var sipProvider: SipProvider = null
    var headerFactory: HeaderFactoryImpl = null
    var messageFactory: MessageFactory = null
    var addressFactory: AddressFactory = null

    def createSipStack(): Unit = {
        // create sip stack:
        val sipFactory = SipFactory.getInstance
        sipFactory.setPathName("gov.nist")
        sipStack = sipFactory.createSipStack(sipStackProperties())
        // setup factories:
        addressFactory = sipFactory.createAddressFactory()
        headerFactory = sipFactory.createHeaderFactory().asInstanceOf[HeaderFactoryImpl]
        messageFactory = sipFactory.createMessageFactory()
        // add udp listening point:
        sipListeningPoint = sipStack.createListeningPoint(bindAddr.getHostString, bindAddr.getPort, "udp")
        // set up sip provider:
        sipProvider = sipStack.createSipProvider(sipListeningPoint)
        // set sip listener:
        sipProvider.addSipListener(this)
    }
}

trait SipStackProperties
{
    def TRACE_LEVEL: String = "0"

    def sipStackProperties(): Properties = {
        val properties = new Properties()
        // Turn off automatic dialog support.
//        properties.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", "off")
        properties.setProperty("javax.sip.STACK_NAME", "HSC")
        // The following properties are specific to nist-sip
        // and are not necessarily part of any other jain-sip
        // implementation.
        // You can set a max message size for tcp transport to
        // guard against denial of service attack.
        properties.setProperty("gov.nist.javax.sip.MAX_MESSAGE_SIZE", "1048576")
        //        properties.setProperty("gov.nist.javax.sip.DEBUG_LOG",
        //            "clientdebug.txt");
        //        properties.setProperty("gov.nist.javax.sip.SERVER_LOG",
        //            "clientlog.txt");
        // Drop the client connection after we are done with the transaction.
        properties.setProperty("gov.nist.javax.sip.CACHE_CLIENT_CONNECTIONS", "false")
        // Set to 0 in your production code for max speed.
        // You need 16 for logging traces. 32 for debug + traces.
        // Your code will limp at 32 but it is best for debugging.
        properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", TRACE_LEVEL)
        //        properties.setProperty("gov.nist.javax.sip.STACK_LOGGER", Log4JLogger.class.getName());
        properties
    }
}
