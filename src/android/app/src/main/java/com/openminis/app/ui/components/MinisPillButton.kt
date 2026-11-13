package com.openminis.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.MinisDesign

/** [T-ui-refresh] 胶囊按钮三态：主色实心 / 色调底 / 幽灵。 */
enum class MinisPillStyle { Primary, Tonal, Ghost }

/**
 * [T-ui-refresh] 统一胶囊按钮。参照 React Bits 与 Linear 的 pill 规格：
 * 13sp 半粗、水平 16dp、垂直 8dp、全圆角，点击区域仍满足 44dp 触控。
 */
@Composable
fun MinisPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MinisPillStyle = MinisPillStyle.Primary,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when (style) {
        MinisPillStyle.Primary -> scheme.primary
        MinisPillStyle.Tonal -> scheme.secondaryContainer
        MinisPillStyle.Ghost -> scheme.surfaceContainerHighest
    }
    val fg = when (style) {
        MinisPillStyle.Primary -> scheme.onPrimary
        MinisPillStyle.Tonal -> scheme.onSecondaryContainer
        MinisPillStyle.Ghost -> scheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(MinisDesign.Radius.pill),
        color = bg,
        contentColor = fg,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
