package com.juanlink.core

/**
 * 婵娟 (JUAN Link) 全局元信息。
 *
 * 版本与标识常量，供协议魔数、日志、UI 各处引用，保证全局一致。
 */
object AppInfo {
    /** 应用名 */
    const val APP_NAME: String = "婵娟 JUAN Link"

    /** 版本 */
    const val VERSION: String = "1.0.0"

    /** 协议魔数：用于区分本协议数据与其他二进制流 */
    const val MAGIC: String = "juanlink"

    /** 协议版本 */
    const val PROTOCOL_VERSION: Int = 1

    /** 默认会话有效期（秒），二维码/配对码自动过期 */
    const val DEFAULT_PAIRING_EXPIRES_SEC: Long = 180
}
