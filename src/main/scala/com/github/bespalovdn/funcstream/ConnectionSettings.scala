package com.github.bespalovdn.funcstream

case class ConnectionSettings
(
    isDebugEnabled: Boolean
)

object ConnectionSettings
{
    def default: ConnectionSettings = new ConnectionSettings(
        isDebugEnabled = false
    )
}