package com.juanlink.composeui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juanlink.composeui.theme.Palette
import com.juanlink.core.transport.TurnServer

/**
 * TURN 服务器设置面板：
 * 用户填写自己的免费 TURN 凭据（Cloudflare 免费 API / Metered 20GB 免费账号 / 自建 coturn）。
 * 保存后自定义服务器优先尝试，默认公共列表仍作 fallback；清空列表 = 仅用公共列表。
 */
@Composable
fun SettingsPanel(
    servers: List<TurnServer>,
    onSave: (List<TurnServer>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,       // 引导锁定态：隐藏关闭按钮、显示引导标题与跳过出口
    onSkip: (() -> Unit)? = null,  // 锁定态下的「跳过（仅局域网直连）」出口
) {
    // SnapshotStateList：元素就地修改也触发重组（普通 List 改元素不触发）
    val rows = remember { mutableStateListOf<TurnServer>().apply { addAll(servers) } }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.XuanZhiBai)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                if (locked) "首次使用 · 请先配置 TURN" else "Juan LinK · TURN 设置",
                color = Palette.MoHei,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
            // 锁定态隐藏关闭按钮（引导不可关闭，只能保存或跳过）
            if (!locked) {
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
        }

        Text(
            "跨网连接（对称 NAT 两侧无法打洞）需 TURN 中继兜底。公共服务器免费但不可靠，" +
                "建议填写自己的免费 TURN 凭据（Cloudflare 免费 API、Metered 20GB/月、或自建 coturn）。" +
                "注意：两台设备需配置相同的服务器，中继才可互通。",
            color = Palette.HuiMo,
            fontSize = 12.sp,
        )
        if (locked) {
            Text(
                "跨网协作必须先配置服务器；仅局域网直连可点「跳过」继续。",
                color = Palette.ZhuShaHong,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // 服务器列表（限高滚动，适配小屏）
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEachIndexed { i, s ->
                ServerRow(
                    server = s,
                    title = "服务器 ${i + 1}",
                    onDelete = { rows.removeAt(i) },
                    onChange = { rows[i] = it },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelButton("添加服务器", Palette.ZhuQing.copy(alpha = 0.85f)) { rows.add(TurnServer("", 3478, "", "")) }
            Spacer(Modifier.weight(1f))
            // 锁定引导下的「仅局域网」出口：不配置也可继续局域网直连
            if (locked && onSkip != null) {
                PanelButton("跳过（仅局域网）", Palette.HuiMo.copy(alpha = 0.55f)) { onSkip() }
            }
            PanelButton("保存", Palette.ZhuQing) { onSave(rows.filter { it.host.isNotBlank() }) }
        }
    }
}

@Composable
private fun ServerRow(server: TurnServer, title: String, onDelete: () -> Unit, onChange: (TurnServer) -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.DanMo.copy(alpha = 0.35f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(title, color = Palette.MoHei, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.ZhuShaHong.copy(alpha = 0.12f))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("删除", color = Palette.ZhuShaHong, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = server.host,
                onValueChange = { onChange(server.copy(host = it)) },
                label = { Text("host") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = server.port.toString(),
                onValueChange = { onChange(server.copy(port = it.toIntOrNull() ?: server.port)) },
                label = { Text("port") },
                singleLine = true,
                modifier = Modifier.width(84.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = server.username,
                onValueChange = { onChange(server.copy(username = it)) },
                label = { Text("username") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = server.password,
                onValueChange = { onChange(server.copy(password = it)) },
                label = { Text("password") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PanelButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}
