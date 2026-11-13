package com.openminis.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * [T-ui-refresh] 会话分类与状态色板。
 *
 * 这些颜色是语义色（分类图标色、成功/警告/离线状态），不跟 Material3
 * 主题走：换肤时它们要保持可辨识度。集中到一处之后，页面里不再散落
 * `Color(0xFFF09A37)` 这种裸值。
 */
object CategoryPalette {
    val Code = Color(0xFFF09A37)
    val Writing = Color(0xFF3478F6)
    val Research = Color(0xFF30B0C7)
    val Analysis = Color(0xFF5856D6)
    val Creative = Color(0xFFFF2D55)
    val Chat = Color(0xFF34C759)
    val Math = Color(0xFF9B59B6)
    val Translation = Color(0xFF00BCD4)
    val Health = Color(0xFFFF3B30)
    val Finance = Color(0xFF00C7BE)
    val Productivity = Color(0xFFFFCC00)
    val Support = Color(0xFF8B6914)
    val Neutral = Color(0xFF8E8E93)

    /** 成功 / 完成。 */
    val Success = Color(0xFF34C759)

    /** 警告 / 待处理。 */
    val Warning = Color(0xFFFF9500)
}
