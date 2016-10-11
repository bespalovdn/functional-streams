package com.github.bespalovdn.fs

class ActionFailedException(cause: String) extends Exception(cause)
class StreamClosedException(cause: Throwable) extends Exception(cause)
