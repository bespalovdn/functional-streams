package com.github.bespalovdn.funcstream.concurrent

import java.util.concurrent.locks.ReentrantReadWriteLock

class SmartLock{
    private val lock = new ReentrantReadWriteLock(true)

    def withReadLockDo[A](fn: => A): A = {
        val l = lock.readLock()
        l.lock()
        try{
            fn
        }finally{
            l.unlock()
        }
    }

    def withWriteLockDo[A](fn: => A): A = {
        val l = lock.writeLock()
        l.lock()
        try{
            fn
        }finally{
            l.unlock()
        }
    }
}
