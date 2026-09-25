package com.juanlink.composeui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import io.ak1.drawbox.domain.model.TextAlignment

/** 文本编辑字号预设（与 ContextStyleBar 新建默认一致） */
private val DIALOG_FONT_SIZES = listOf(12f, 16f, 24f, 32f, 48f)

/** 文本编辑字体族选项（key → 标签） */
private val DIALOG_FONT_FAMILIES = listOf(
    "sans" to "默认",
    "serif" to "衬线",
    "mono" to "等宽",
)

/** 文本对齐选项 */
private val DIALOG_ALIGNMENTS = listOf(
    TextAlignment.LEFT to "左",
    TextAlignment.CENTER to "中",
    TextAlignment.RIGHT to "右",
)

/**
 * 文本编辑浮层（覆盖层模式，与 ConnectionPanel/SettingsPanel 同构，双端一致）。
 *
 * 触发：Mode.TEXT 点击插入空文本、SELECT 模式双击 / 二次点击已选中的文本元素。
 * 除文本内容外还可设置**字号 / 对齐 / 字体**（随提交一起同步）。
 * 确认 → [onCommit]；取消或点遮罩 → [onDismiss]（宿主侧会清理空占位元素）。
 */
@Composable
fun TextEditOverlay(
    title: String,
    initialText: String,
    initialFontSize: Float,
    initialAlignment: TextAlignment,
    initialFontFamily: String,
    onCommit: (text: String, fontSize: Float, alignment: TextAlignment, fontFamily: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(initialText) }
    var fontSize by remember { mutableStateOf(initialFontSize) }
    var alignment by remember { mutableStateOf(initialAlignment) }
    var fontFamily by remember { mutableStateOf(initialFontFamily) }
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
                .widthIn(max = 420.dp)
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

            // 字号
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("字号", color = Palette.HuiMo, fontSize = 12.sp)
                for (s in DIALOG_FONT_SIZES) {
                    DialogChip(
                        label = "${s.toInt()}",
                        selected = fontSize == s,
                        onClick = { fontSize = s },
                    )
                }
            }

            // 对齐
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("对齐", color = Palette.HuiMo, fontSize = 12.sp)
                for ((al, label) in DIALOG_ALIGNMENTS) {
                    DialogChip(
                        label = label,
                        selected = alignment == al,
                        onClick = { alignment = al },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("字体", color = Palette.HuiMo, fontSize = 12.sp)
                for ((key, label) in DIALOG_FONT_FAMILIES) {
                    DialogChip(
                        label = label,
                        selected = fontFamily == key,
                        onClick = { fontFamily = key },
                    )
                }
            }

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
                        .clickable(onClick = { onCommit(text, fontSize, alignment, fontFamily) })
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("确定", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun DialogChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Palette.ZhuQing else Palette.DanMo.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else Palette.HuiMo,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
