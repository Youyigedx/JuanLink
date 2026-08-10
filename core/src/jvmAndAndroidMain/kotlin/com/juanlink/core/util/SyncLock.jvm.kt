package com.juanlink.core.util

/** JVM/Android 目标：synchronized 互斥锁（可重入） */
actual class SyncLock actual constructor() {
    private val monitor = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
