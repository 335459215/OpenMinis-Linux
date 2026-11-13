package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.MinisDesign

/**
 * [T-ui-refresh] 统一卡片容器：surfaceContainerHigh 底 + 1dp outlineVariant
 * 描边 + 16dp 圆角，可选点击波纹，header / footer 两个 slot。
 *
 * 视觉参照 Awwwards 上常见的「轻容器 + 细描边」层叠方式：容器本身不抢
 * 内容，只靠一条 hairline 和半级色差分层。
 */
@Composable
fun MinisSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(MinisDesign.Radius.md)
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(MinisDesign.Space.md)) {
            header?.invoke()
            if (header != null) Spacer(Modifier.height(MinisDesign.Space.sm))
            content()
            footer?.let {
                Spacer(Modifier.height(MinisDesign.Space.sm))
                it()
            }
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
            content = body,
        )
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = body)
    }
}
