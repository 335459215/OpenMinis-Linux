package com.openminis.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * [T-ui-refresh] 工具卡类型色板。
 *
 * 工具种类靠颜色快速区分（终端绿、文件蓝、编辑橙…），这套色是语义色，
 * 不随 Material3 主题漂移。集中在此，页面里不再出现裸 Color(0xFF...)。
 */
object ToolPalette {
    val Green = Color(0xFF34C759)
    val Cyan = Color(0xFF32ADE6)
    val Blue = Color(0xFF007AFF)
    val Orange = Color(0xFFFF9500)
    val Neutral = Color(0xFF8E8E93)
    val Purple = Color(0xFFAF52DE)
    val Pink = Color(0xFFFF2D55)
    val Sky = Color(0xFF64D2FF)
    val Indigo = Color(0xFF5856D6)
    val Teal = Color(0xFF30B0C7)
}
