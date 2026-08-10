package com.juanlink.android.io

import android.content.Context
import com.juanlink.composeui.platform.SnapshotIo
import java.io.File

/** Android 快照持久化：应用私有 filesDir/juanlink/snapshots.json */
class AndroidSnapshotIo(context: Context) : SnapshotIo {
    private val file = File(context.filesDir, "juanlink/snapshots.json")
    override fun load(): String? = runCatching {
        if (file.exists()) file.readText() else null
    }.getOrNull()
    override fun save(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
    }
}
