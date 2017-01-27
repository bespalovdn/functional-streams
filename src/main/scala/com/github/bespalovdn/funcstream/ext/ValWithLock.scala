package com.github.bespalovdn.funcstream.ext

import java.util.concurrent.locks.ReentrantReadWriteLock


class ValWithLock[A](value: A)
{
    private val lock = new ReentrantReadWriteLock()

    def withWriteLock[B](fn: A => B): B = {
        val l = lock.writeLock()
        l.lock()
        try fn(value) finally l.unlock()
    }

    def withReadLock[B](fn: A => B): B = {
        val l = lock.readLock()
        l.lock()
        try fn(value) finally l.unlock()
    }
}