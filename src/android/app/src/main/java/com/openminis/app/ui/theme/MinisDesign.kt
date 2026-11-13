package com.openminis.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/**
 * [T-ui-refresh] Minis Ultra 统一设计令牌。
 *
 * 页面里不再散落 12.dp / 16.dp / 999.dp 这类魔法值：圆角、间距、动效时长
 * 全部从这里取，网关门面（原生 View）也用同一组数值，避免 Compose 与
 * 原生页出现两种时代感。
 */
object MinisDesign {
    object Radius {
        val xs = 8.dp
        val sm = 12.dp
        val md = 16.dp
        val lg = 20.dp
        val pill = 999.dp
    }

    object Space {
        val xxs = 4.dp
        val xs = 8.dp
        val sm = 12.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 32.dp
    }

    object Motion {
        const val FAST = 140
        const val BASE = 220
        const val SLOW = 320
    }
}

@Composable
@ReadOnlyComposable
fun minisCardShape() = RoundedCornerShape(MinisDesign.Radius.md)

@Composable
@ReadOnlyComposable
fun minisPillShape() = RoundedCornerShape(MinisDesign.Radius.pill)
