package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-plan-locate] 任务板行点击 → 聊天列表滚动请求。
 *
 * 放成独立 object 而不是塞进 ChatViewModel：ChatViewModel 已顶到架构行数上限，
 * 这条请求也不需要 ViewModel 的生命周期语义，UI 消费一次即清空。
 */
internal object PlanLocateBus {
    private val _request = MutableStateFlow<String?>(null)
    val request: StateFlow<String?> = _request.asStateFlow()

    fun request(title: String) {
        if (title.isNotBlank()) _request.value = title
    }

    fun consume() {
        _request.value = null
    }
}

/** 折叠状态：items 已过滤，counts 供头部箭头判断，collapsed 供渲染，toggle 供点击。 */
internal class RunFoldState(
    val items: List<FlatChatItem>,
    val counts: Map<String, Int>,
    val collapsed: Set<String>,
    val toggle: (String) -> Unit,
)

/** [T-run-fold] 轮级折叠：默认展开，点助手消息头的箭头收起该轮全部运行记录。 */
@Composable
internal fun rememberChatRunFold(
    flatItems: List<FlatChatItem>,
    sessionId: String,
): RunFoldState {
    val collapsed = remember(sessionId) { mutableStateOf(emptySet<String>()) }
    val counts = remember(flatItems) { runRecordCounts(flatItems) }
    val items = remember(flatItems, collapsed.value) {
        applyRunCollapse(flatItems, collapsed.value)
    }
    return RunFoldState(items, counts, collapsed.value) { id ->
        collapsed.value = if (id in collapsed.value) {
            collapsed.value - id
        } else {
            collapsed.value + id
        }
    }
}

/** 监听 [PlanLocateBus]，把列表滚到创建该任务的那条工具调用行。 */
@Composable
internal fun PlanLocateEffect(
    flatItems: List<FlatChatItem>,
    listState: LazyListState,
) {
    val request by PlanLocateBus.request.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        val planTitle = request ?: return@LaunchedEffect
        val targetIdx = flatItems.indexOfFirst { row ->
            val block = (row as? FlatChatItem.AssistantToolUse)?.block
            block != null &&
                (block.toolArgs.contains(planTitle) || block.content.contains(planTitle))
        }
        if (targetIdx >= 0) {
            listState.animateScrollToItem((flatItems.size - 1 - targetIdx).coerceAtLeast(0))
        }
        PlanLocateBus.consume()
    }
}
