package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.service.SubAgentActivityTracker
import com.openminis.app.tools.AgentPlanStore

/**
 * 顶栏第二排：清单 / 子代理，两个都是抽屉式，默认折叠。
 *
 * [T-topright-second-row-in-topbar] 用户要求「四个按钮上下对齐、左右对称」：
 * 第一行是发送模式胶囊 + 三点菜单，这一行是清单 + 子代理，两行共享右边缘。
 * 所以本组件**不再**占满一行宽度（`fillMaxWidth` 已去掉），而是作为一个
 * 紧凑的右对齐 Column 嵌进顶栏的 actions 槽里（见 ChatTopBarActions）。
 *
 * 展开列表用 [Popup] 而不是就地展开：顶栏是 SubcomposeLayout，就地长高会
 * 让整条顶栏跳动，并把下面的消息列表推下去。Popup 挂在胶囊下方，与胶囊
 * 右边缘对齐，顶栏高度不变。
 *
 * 无内容时不渲染（清单空且无子代理），保持「有活才有条」的语义。
 */
@Composable
internal fun ChatTopRightSecondRow(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val sid = viewModel.activeSessionId
    val planVersion by AgentPlanStore.version.collectAsStateWithLifecycle()
    val plans = remember(planVersion, sid) {
        runCatching { AgentPlanStore.list(sid, context) }.getOrDefault(emptyList())
    }
    val members by SubAgentActivityTracker.members.collectAsStateWithLifecycle()
    val mine = remember(members, sid) { members.filter { it.parentSessionId == sid } }

    var planOpen by rememberSaveable(sid) { mutableStateOf(false) }
    var subOpen by rememberSaveable(sid) { mutableStateOf(false) }

    // Nothing to show → take no vertical space at all.
    if (plans.isEmpty() && mine.isEmpty()) {
        if (planOpen) planOpen = false
        if (subOpen) subOpen = false
        return
    }

    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                DrawerPill(
                    text = "清单 ${plans.count { it.status == "done" }}/${plans.size}",
                    expanded = planOpen,
                ) { planOpen = !planOpen }
                if (planOpen) {
                    Popup(
                        onDismissRequest = { planOpen = false },
                        alignment = Alignment.TopEnd,
                        properties = PopupProperties(focusable = true),
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .widthIn(max = 320.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (plans.isEmpty()) {
                                Text(
                                    "暂无任务",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                plans.forEach { p ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    ) {
                                        Text(
                                            statusMark(p.status),
                                            fontSize = 11.sp,
                                            modifier = Modifier.width(16.dp),
                                        )
                                        Text(
                                            p.title,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                DrawerPill(text = "子代理 ${mine.size}", expanded = subOpen) { subOpen = !subOpen }
                if (subOpen) {
                    Popup(
                        onDismissRequest = { subOpen = false },
                        alignment = Alignment.TopEnd,
                        properties = PopupProperties(focusable = true),
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .widthIn(max = 340.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (mine.isEmpty()) {
                                Text(
                                    "当前没有子代理",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                mine.forEach { m ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    ) {
                                        Box(
                                            Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(phaseColor(m.phase)),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            m.title.ifBlank { m.role ?: "子代理" },
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 140.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            m.phase.ifBlank { "思考中" } +
                                                if (m.currentTool.isNotBlank()) " · ${m.currentTool}" else "",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 110.dp),
                                        )
                                        IconButton(
                                            onClick = { SubAgentActivityTracker.stop(m.id) },
                                            modifier = Modifier.size(26.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.Stop,
                                                contentDescription = "停止",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerPill(text: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusMark(status: String): String = when (status) {
    "done" -> "✓"
    "active" -> "●"
    "failed" -> "!"
    else -> "○"
}

private fun phaseColor(phase: String): Color = when (phase) {
    "调用工具" -> Color(0xFFFF9500)
    "回复中" -> Color(0xFF34C759)
    "执行中" -> Color(0xFF5856D6)
    else -> Color(0xFF007AFF)
}
