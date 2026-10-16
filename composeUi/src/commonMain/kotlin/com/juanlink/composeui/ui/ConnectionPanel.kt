package com.juanlink.composeui.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.juanlink.composeui.platform.toComposeImage
import com.juanlink.composeui.theme.Palette
import com.juanlink.core.pairing.PairingCodec
import com.juanlink.core.pairing.PairingInfo
import com.juanlink.core.qr.QrCodec

/**
 * 连接面板：创建（二维码 + 配对码）/ 加入（输入配对码 / 可选扫码）。
 */
@Composable
fun ConnectionPanel(
    pairing: PairingInfo?,
    pairingCode: String?,
    statusText: String,
    isConnected: Boolean,
    onClose: () -> Unit,
    onJoin: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    onScanClick: (() -> Unit)? = null,
    /** 短码是否由会合服务器下发（false=仅本机可用，UI 需给出提示与兜底引导） */
    codeIsRemote: Boolean = true,
    /** 是否已配置会合服务器（决定加入区的提示与输入框标签） */
    shortCodeEnabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.XuanZhiBai)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text("Juan LinK · 连接", color = Palette.MoHei, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
            // 关闭按钮
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("×", color = Palette.HuiMo, fontSize = 20.sp)
            }
        }

        if (pairing != null) {
            val qrBitmap = remember(pairing) {
                runCatching { QrCodec.encode(PairingCodec.toQrPayload(pairing), scale = 6) }.getOrNull()
            }
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.toComposeImage(),
                    contentDescription = "连接二维码",
                    // 300dp：此前 200dp 时版本12 的二维码每模块仅约 3px，手机相机很难扫出
                    modifier = Modifier.size(300.dp),
                )
            } else {
                Text("二维码生成失败", color = Palette.ZhuShaHong, fontSize = 13.sp)
            }
            val clipboard = LocalClipboardManager.current
            var copyHint by remember { mutableStateOf("") }
            LaunchedEffect(copyHint) {
                if (copyHint.isNotEmpty()) {
                    delay(2000)
                    copyHint = ""
                }
            }
            val connectionString = remember(pairing) { PairingCodec.toQrPayload(pairing) }

            if (pairingCode != null) {
                Text(
                    if (codeIsRemote) "对方输入这个配对码即可加入：" else "配对码（仅本机可用）：",
                    color = Palette.HuiMo,
                    fontSize = 13.sp,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(pairingCode))
                            copyHint = "配对码已复制"
                        }
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(pairingCode, color = Palette.ZhuShaHong, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                if (!codeIsRemote) {
                    Text(
                        "未配置会合服务器，此码无法跨设备解析。请让对方扫码，或点下方复制连接串。",
                        color = Palette.ZhuShaHong,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // 兜底通道：扫码不可用 / 未配服务器时仍可手工传递连接串
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.ZhuQing.copy(alpha = 0.15f))
                    .clickable {
                        clipboard.setText(AnnotatedString(connectionString))
                        copyHint = "连接串已复制"
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("复制连接串（备用）", color = Palette.ZhuQing, fontSize = 13.sp)
            }
            if (copyHint.isNotEmpty()) {
                Text(copyHint, color = Palette.ZhuQing, fontSize = 12.sp)
            }
        } else {
            JoinBox(onJoin = onJoin, onScanClick = onScanClick, shortCodeEnabled = shortCodeEnabled)
        }

        Text(statusText, color = Palette.HuiMo, fontSize = 13.sp, textAlign = TextAlign.Center)

        if (isConnected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.ZhuShaHong)
                    .clickable { onDisconnect() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("断开连接", color = ColorWhite, fontSize = 14.sp)
            }
        }
    }
}

private val ColorWhite = androidx.compose.ui.graphics.Color.White

@Composable
private fun JoinBox(onJoin: (String) -> Unit, onScanClick: (() -> Unit)?, shortCodeEnabled: Boolean) {
    var payload by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            label = { Text(if (shortCodeEnabled) "输入配对码（如 5689-ABCN）" else "粘贴对方连接串") },
            singleLine = shortCodeEnabled,
            minLines = if (shortCodeEnabled) 1 else 3,
            modifier = Modifier.width(320.dp),
        )
        if (!shortCodeEnabled) {
            Text(
                "尚未配置会合服务器，短配对码不可用。请在「设置」中填写你的服务器地址，" +
                    "或让对方点「复制连接串（备用）」发给你。",
                color = Palette.HuiMo,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.ZhuQing)
                .clickable { onJoin(payload.trim()) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("加入协作", color = ColorWhite, fontSize = 14.sp)
        }
        if (onScanClick != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.ZhuQing.copy(alpha = 0.15f))
                    .clickable { onScanClick() }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text("扫码加入", color = Palette.ZhuQing, fontSize = 14.sp)
            }
        }
    }
}
