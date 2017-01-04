package com.github.bespalovdn.funcstream

import scala.concurrent.Future

package object examples {
    class ActionFailedException(cause: String) extends Exception(cause)

    def fail[A](cause: String): Future[A] = Future.failed(new ActionFailedException(cause))
}
