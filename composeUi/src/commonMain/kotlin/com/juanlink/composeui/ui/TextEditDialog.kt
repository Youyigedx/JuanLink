package com.juanlink.composeui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juanlink.composeui.theme.Palette

/**
 * 文本编辑浮层（覆盖层模式，与 ConnectionPanel/SettingsPanel 同构，双端一致）。
 *
 * 触发：Mode.TEXT 点击插入空文本、SELECT 模式双击 / 二次点击已选中的文本元素。
 * 确认 → [onCommit]（UpdateText 同步）；取消或点遮罩 → [onDismiss]
 * （宿主侧会清理空占位元素）。弹出即聚焦输入框。
 */
@Composable
fun TextEditOverlay(
    title: String,
    initialText: String,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.XuanZhiBai)
                .padding(20.dp)
                // 阻止点击穿透到遮罩（取消需点按钮或遮罩空白区）
                .clickable(enabled = false) {},
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Palette.MoHei, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("输入文字…", color = Palette.HuiMo, fontSize = 14.sp) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("取消", color = Palette.HuiMo, fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.ZhuQing)
                        .clickable(onClick = { onCommit(text) })
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("确定", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}
