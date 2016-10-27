package com.github.bespalovdn.fs.examples.sipproxy

import com.github.bespalovdn.fs._
import com.github.bespalovdn.fs.examples.sip.SipMessage

import scala.concurrent.{Future, ExecutionContext}

trait SipCommons
{
    type Consumer[A] = ConstConsumer[SipMessage, SipMessage, A]

    implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

    def repeatOnFail[A](f: => Future[A]): Future[A] = f.recoverWith{case e: MatchError => repeatOnFail(f)}
}
