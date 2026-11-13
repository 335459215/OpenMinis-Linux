package com.openminis.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * [T-ui-refresh] 状态色板：成功 / 警告 / 敏感 / 危险 / 信息 / 中性。
 *
 * 这些是跨页面复用的语义色（网络状态、配置风险、审计结果、日志级别），
 * 统一从一处取，页面里不再散落裸 Color(0xFF...)。
 */
object StatusPalette {
    val Success = Color(0xFF34C759)
    val Warning = Color(0xFFFF9500)
    val Sensitive = Color(0xFFE65100)
    val Danger = Color(0xFFFF3B30)
    val Info = Color(0xFF1976D2)
    val Applied = Color(0xFF388E3C)
    val Reverted = Color(0xFF8E5CD9)
    val Muted = Color(0xFF757575)
    val LevelBlue = Color(0xFF3B82F6)
    val LevelGreen = Color(0xFF34C759)
    val LevelOrange = Color(0xFFFF9F0A)
    val LevelRed = Color(0xFFFF3B30)
}
