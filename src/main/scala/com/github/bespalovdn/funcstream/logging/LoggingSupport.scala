package com.github.bespalovdn.funcstream.logging

import org.slf4j.LoggerFactory

trait LoggingSupport
{
    def name: String
    def isDebugEnabled: Boolean

    private lazy val logger = LoggerFactory.getLogger(getClass)

    def debug(msg: => String): Unit = if(isDebugEnabled) {
        val prefix = Option(name).map(_ + ": ").getOrElse("")
        logger.debug(prefix + msg)
    }
}
