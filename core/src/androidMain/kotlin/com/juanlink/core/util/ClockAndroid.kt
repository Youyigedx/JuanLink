package com.juanlink.core.util

/** Android 平台实际：系统墙钟毫秒 */
actual fun nowEpochMillis(): Long = System.currentTimeMillis()
