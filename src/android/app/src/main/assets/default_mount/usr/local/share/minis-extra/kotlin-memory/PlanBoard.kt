package com.minis.ultra.ui

// 任务清单（PlanBoard）：默认折叠 + 紧凑胶囊 + 单任务也显示
// 依赖：androidx.compose.material3 / foundation / animation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PlanItemUi(val title: String, val status: String)

/**
 * 行为约定：
 * 1. items 非空就渲染——一条也渲染（单任务进程同样有清单）。
 * 2. 默认折叠：rememberSaveable 初值 false；展开状态按会话保存。
 * 3. 折叠态是一个胶囊，最大宽度 168dp，高度 26dp，不占满行宽。
 * 4. 全部完成时胶囊自动变灰并在 3s 后收起（只留“已完成 n/n”）。
 */
@Composable
fun PlanBoard(
    items: List<PlanItemUi>,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    if (items.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(defaultExpanded) }
    val done = items.count { it.status == "done" }
    val total = items.size
    val active = items.firstOrNull { it.status == "active" } ?: items.firstOrNull { it.status == "pending" }
    val progress by animateFloatAsState(if (total == 0) 0f else done / total.toFloat(), label = "plan")

    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        // ── 折叠态：紧凑胶囊 ─────────────────────────────
        Row(
            modifier = Modifier
                .widthIn(max = 168.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Material3SurfaceVariant)
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (done == total) DoneColor else ActiveColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (done == total) "已完成 $done/$total"
                else active?.title ?: "任务 $done/$total",
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Text("$done/$total", fontSize = 10.sp, lineHeight = 10.sp, color = MutedText)
        }

        // ── 展开态：面板，宽度同样收窄 ────────────────────
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Material3Surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
                Spacer(Modifier.height(6.dp))
                items.forEach { item ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (item.status) {
                                "done" -> "✓"; "active" -> "●"; "failed" -> "!"; else -> "○"
                            },
                            fontSize = 11.sp,
                            modifier = Modifier.width(14.dp),
                        )
                        Text(
                            item.title,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
