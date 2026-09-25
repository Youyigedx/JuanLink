package com.juanlink.composeui.theme

import androidx.compose.ui.graphics.Color

/**
 * 中式色板（画笔 / 描边 / 填充 / 背景共用）。
 * 从 ToolBar 提取为共享常量，避免样式栏重复定义。
 */
val INK_COLORS = listOf(
    Color(0xFF1A1A1A),  // 墨黑
    Color(0xFF2F6F5E),  // 竹青
    Color(0xFFC0392B),  // 朱砂红
    Color(0xFF7A4E2D),  // 赭石
    Color(0xFF3D5A80),  // 黛蓝
    Color(0xFF9C6B30),  // 赭黄
)
