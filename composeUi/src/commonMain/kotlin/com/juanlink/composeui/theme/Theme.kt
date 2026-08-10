@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTextApi::class)

package com.juanlink.composeui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import com.juanlink.composeui.platform.SongFontFamily

/**
 * 中式简约风配色（宣纸白/墨黑/竹青/朱砂红）。
 */
object Palette {
    val XuanZhiBai = Color(0xFFF7F3E9)   // 宣纸白：背景/画布底
    val MoHei = Color(0xFF1A1A1A)        // 墨黑：主文字/墨迹
    val ZhuQing = Color(0xFF2F6F5E)      // 竹青：主色/按钮/选中态
    val ZhuShaHong = Color(0xFFC0392B)   // 朱砂红：强调/错误/当前工具
    val HuiMo = Color(0xFF6B6258)        // 灰墨：次级文字/图标
    val DanMo = Color(0xFFE5DED2)        // 淡墨：边框/分隔线/工具栏底
    val FeiBai = Color(0x14FFFFFF)       // 飞白：半透明遮罩

    val XuanZhiBaiDark = Color(0xFFEDE6D6)  // 深宣纸：网格/留白
}

@Composable
fun JuanTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val songTypography = base.copy(
        bodyLarge = base.bodyLarge.copy(fontFamily = SongFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = SongFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = SongFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = SongFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = SongFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = SongFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = SongFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = SongFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = SongFontFamily),
    )
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.ZhuQing,
            onPrimary = Color.White,
            secondary = Palette.ZhuShaHong,
            background = Palette.XuanZhiBai,
            onBackground = Palette.MoHei,
            surface = Palette.XuanZhiBai,
            onSurface = Palette.MoHei,
            surfaceVariant = Palette.DanMo,
            onSurfaceVariant = Palette.HuiMo,
            outline = Palette.DanMo,
        ),
        typography = songTypography,
        content = content,
    )
}
