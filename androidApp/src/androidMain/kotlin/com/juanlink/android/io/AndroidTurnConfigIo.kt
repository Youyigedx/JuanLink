package com.juanlink.android.io

import android.content.Context
import com.juanlink.composeui.platform.TurnConfigIo

/** Android TURN 配置持久化：应用私有 SharedPreferences（无需存储权限，随卸载清除） */
class AndroidTurnConfigIo(context: Context) : TurnConfigIo {
    private val prefs = context.getSharedPreferences("juanlink_turn", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_SERVERS_JSON, null)

    override fun save(text: String) {
        prefs.edit().putString(KEY_SERVERS_JSON, text).apply()
    }

    private companion object {
        const val KEY_SERVERS_JSON = "servers_json"
    }
}
