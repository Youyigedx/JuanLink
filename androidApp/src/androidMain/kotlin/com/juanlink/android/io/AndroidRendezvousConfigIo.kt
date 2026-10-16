package com.juanlink.android.io

import android.content.Context
import com.juanlink.composeui.platform.RendezvousConfigIo

/**
 * Android 会合服务配置持久化：应用私有 SharedPreferences。
 *
 * 存储内容包括服务器地址、端口、协议与可选访问令牌。
 * ⚠️ 该服务器会获得公钥分发权，配置应由用户自行填写自己掌控的服务器。
 */
class AndroidRendezvousConfigIo(context: Context) : RendezvousConfigIo {
    private val prefs = context.getSharedPreferences("juanlink_rendezvous", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_CONFIG_JSON, null)

    override fun save(text: String) {
        prefs.edit().putString(KEY_CONFIG_JSON, text).apply()
    }

    private companion object {
        const val KEY_CONFIG_JSON = "config_json"
    }
}
