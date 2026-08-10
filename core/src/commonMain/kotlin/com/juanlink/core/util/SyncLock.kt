package com.juanlink.core.util

/**
 * 跨平台互斥锁：commonMain 无 JVM synchronized，用 expect/actual 收敛到各平台。
 * 用于保护会被多线程（UI 线程 + 网络读线程）共享的状态（如 OpSyncEngine）。
 */
expect class SyncLock() {
    fun <T> withLock(block: () -> T): T
}
