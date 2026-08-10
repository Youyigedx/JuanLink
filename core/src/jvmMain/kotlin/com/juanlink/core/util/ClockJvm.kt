package com.juanlink.core.util

/** JVM 平台时钟：System.currentTimeMillis() */
actual fun nowEpochMillis(): Long = System.currentTimeMillis()
