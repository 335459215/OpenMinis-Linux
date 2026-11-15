package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.service.SubAgentActivityTracker

/**
 * 子代理抽屉：默认只显示一个胶囊（运行中数量），点开才展开每个子代理的任务。
 * 替代原来横向滚动的 chip 条，避免顶部被挤满。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentDrawerBar(
    sessionId: String,
    modifier: Modifier = Modifier,
    onStop: (String) -> Unit = {},
) {
    val members by SubAgentActivityTracker.members.collectAsStateWithLifecycle()
    val mine = members.filter { it.parentSessionId == sessionId }
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }

    if (mine.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 220.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(phaseColor(mine.first().phase)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "子代理 ${mine.size} 运行中",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起子代理" else "展开子代理",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                mine.forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { detailId = m.id }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(phaseColor(m.phase)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (m.index > 0 && m.total > 0) "${m.index}/${m.total}" else "•",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            text = m.title.ifBlank { m.role ?: "子代理" },
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = m.phase.ifBlank { "思考中" },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (m.currentTool.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = subAgentToolTitle(m.currentTool, ""),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 80.dp),
                            )
                        }
                        IconButton(onClick = { onStop(m.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "停止 ${m.title}",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    val detail = mine.firstOrNull { it.id == detailId }
    if (detail != null) {
        ModalBottomSheet(onDismissRequest = { detailId = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = detail.title.ifBlank { "子代理" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${detail.phase.ifBlank { "思考中" }} · 第 ${detail.turnIndex.coerceAtLeast(1)} 轮",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                detail.steps.takeLast(12).forEach { step ->
                    Text(
                        text = "· " + step.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
            }
        }
    }
}

private fun phaseColor(phase: String): Color = when (phase) {
    "调用工具" -> Color(0xFFFF9500)
    "回复中" -> Color(0xFF34C759)
    "执行中" -> Color(0xFF5856D6)
    else -> Color(0xFF007AFF)
}