package com.github.bespalovdn.funcstream.logging

import com.github.bespalovdn.funcstream.ConnectionSettings
import org.slf4j.LoggerFactory

trait LoggingSupport
{
    def settings: ConnectionSettings
    def name: String

    private lazy val logger = LoggerFactory.getLogger(getClass)

    def debug(msg: => String): Unit = if(settings.isDebugEnabled) {
        val prefix = Option(name).map(_ + ": ").getOrElse("")
        logger.debug(prefix + msg)
    }
}
