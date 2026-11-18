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

    // [T-021] 单一展开状态：清单和子代理是同一行里的两个抽屉，同时展开会
    // 在同一个锚点叠出两个 Popup，用户点哪个都像点错。用一个变量表达「当前
    // 展开的是谁」，两个抽屉天然互斥，也不需要互相记得去关对方。
    var open by rememberSaveable(sid) { mutableStateOf(SecondRowDrawer.NONE) }
    val planOpen = open == SecondRowDrawer.PLAN
    val subOpen = open == SecondRowDrawer.SUB

    // Nothing to show → take no vertical space at all.
    if (plans.isEmpty() && mine.isEmpty()) {
        if (open != SecondRowDrawer.NONE) open = SecondRowDrawer.NONE
        return
    }

    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // [T-021] 空边不可展开：没有任务就不画「清单」这颗胶囊，而不是画出
            // 来再弹一句「暂无任务」。按钮存在即代表有内容，点开必有东西。
            if (plans.isNotEmpty()) Box {
                DrawerPill(
                    text = "清单 ${plans.count { it.status == "done" }}/${plans.size}",
                    expanded = planOpen,
                ) {
                    open = if (planOpen) SecondRowDrawer.NONE else SecondRowDrawer.PLAN
                }
                if (planOpen) {
                    Popup(
                        onDismissRequest = { open = SecondRowDrawer.NONE },
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
            // [T-021] 只有两边都有内容才需要间距：单边时胶囊贴右边缘对齐。
            if (plans.isNotEmpty() && mine.isNotEmpty()) Spacer(Modifier.width(8.dp))
            if (mine.isNotEmpty()) Box {
                DrawerPill(text = "子代理 ${mine.size}", expanded = subOpen) {
                    open = if (subOpen) SecondRowDrawer.NONE else SecondRowDrawer.SUB
                }
                if (subOpen) {
                    Popup(
                        onDismissRequest = { open = SecondRowDrawer.NONE },
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

/** [T-021] 顶栏第二排同一时刻只允许展开一个抽屉。 */
private enum class SecondRowDrawer { NONE, PLAN, SUB }

@Composable
private fun DrawerPill(text: String, expanded: Boolean, onClick: () -> Unit) {
    // [T-018] 无外框：与第一行的发送模式胶囊一致，只留点击区和文字，
    // 不再画圆角底色块。两行视觉重量相同，右边缘才对得齐。
    //
    // 宽度上限是给「大字 + 窄屏」兜底的：TopAppBar 的 actions 是 intrinsic
    // 宽度，第二行一旦比第一行宽，被挤掉的是中间的会话标题与模型名。所以这里
    // 封顶，超出的文字省略，宁可标签短一点也不让标题列塌掉。
    Row(
        modifier = Modifier
            .widthIn(max = 128.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
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
