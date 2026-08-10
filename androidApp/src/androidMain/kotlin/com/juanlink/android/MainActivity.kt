package com.juanlink.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.juanlink.android.io.AndroidSnapshotIo
import com.juanlink.android.io.AndroidTurnConfigIo
import com.juanlink.android.ui.AndroidRoot
import com.juanlink.composeui.AppState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Activity 重建（configChanges 已拦截旋转）保留 AppState；进程被杀则重开会话
            val app = remember {
                AppState(
                    snapshotIo = AndroidSnapshotIo(applicationContext),
                    turnConfigIo = AndroidTurnConfigIo(applicationContext),
                )
            }
            AndroidRoot(app)
        }
    }
}
