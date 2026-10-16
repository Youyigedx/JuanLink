package com.juanlink.core

/**
 * Juan LinK 全局元信息。
 *
 * 版本与标识常量，供协议魔数、日志、UI 各处引用，保证全局一致。
 */
object AppInfo {
    /** 应用名。⚠️ 注意大小写：是 `Juan LinK`（大写 K），与软著申请表一致 */
    const val APP_NAME: String = "Juan LinK"

    /**
     * 软件版本（载体/构建用，三段式）。
     *
     * 平台对版本号格式有硬性要求，必须保持三段式：
     * - Android：`versionCode` 为整数，`versionName` 建议三段式
     * - 桌面端 jpackage：`packageVersion` 必须是 `MAJOR.MINOR.PATCH`
     */
    const val VERSION: String = "1.0.0"

    /**
     * 软件版本（登记与文档页眉写法）。
     *
     * 中国版权保护中心建议的版本号写法为 `V1.0` 或 `1.0`。
     * 软著申请表的版本号、交存源程序页眉、文档页眉**三处必须逐字一致**，
     * 均使用本常量；`VERSION` 是其在各平台构建产物中的等价三段式表达。
     *
     * 生成交存材料时请传：`--version V1.0`
     */
    const val DISPLAY_VERSION: String = "V1.0"

    /** 协议魔数：用于区分本协议数据与其他二进制流 */
    const val MAGIC: String = "juanlink"

    /** 协议版本 */
    const val PROTOCOL_VERSION: Int = 1

    /** 默认会话有效期（秒），二维码/配对码自动过期 */
    const val DEFAULT_PAIRING_EXPIRES_SEC: Long = 180
}
