package com.juanlink.core.util

/**
 * 平台时钟抽象：commonMain 无 System.currentTimeMillis，
 * 用 expect/actual 收敛到各平台 epoch 毫秒实现。
 */
expect fun nowEpochMillis(): Long
