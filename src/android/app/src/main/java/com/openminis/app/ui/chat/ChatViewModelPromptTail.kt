package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition

internal fun ChatViewModel.taskAnchorFragment(): String? = TaskAnchor.fragment(
    anchorFromDb = _taskAnchorText.value,
    firstUserInWindow = _messages.value
        .firstOrNull { it.role == "user" && it.content.isNotBlank() }?.content,
)

internal fun assembleDynamicTail(
    worldBookFragment: String,
    learnedPrefsFragment: String?,
    recalledMemoryFragment: String?,
    runtimeContext: String,
    personalityReminder: String?,
    taskAnchor: String? = null,
    /** [GH#357] Daily-log memory window: volatile, so it belongs in this tail. */
    dailyMemoryFragment: String? = null,
): String = buildString {
    if (worldBookFragment.isNotEmpty()) append(worldBookFragment)
    if (learnedPrefsFragment != null) {
        append("\n\n")
        append(learnedPrefsFragment)
    }
    if (recalledMemoryFragment != null && recalledMemoryFragment.isNotBlank()) {
        append("\n\n")
        append(recalledMemoryFragment)
    }
    // [GH#357] After recall and before the task anchor: the daily window is
    // per-turn context (a memory_write changes it), so it must never sit in
    // the cached prefix. Keeping it adjacent to the other memory fragments
    // preserves the model-facing reading order.
    if (dailyMemoryFragment != null && dailyMemoryFragment.isNotBlank()) {
        append("\n\n")
        append(dailyMemoryFragment)
    }
    // [T-task-anchor] Ahead of the runtime context: the anchor is stable
    // across turns (unlike the date line below it) and belongs with the
    // other per-turn fragments, not in the stable prefix where one session's
    // opening turn would invalidate the shared cache for every other turn.
    if (taskAnchor != null && taskAnchor.isNotBlank()) {
        append("\n\n")
        append(taskAnchor)
    }
    append(runtimeContext)
    if (personalityReminder != null) {
        append("\n\n")
        append(personalityReminder)
    }
}

/**
 * [GH#563] Re-stamp the day in the runtime-context line of an already-built
 * system prompt.
 *
 * 中文：时间快照必须随轮刷新。长期会话/AI 长循环里系统提示只组装一次，
 * 日期会停留在会话开始那天；这里只替换动态尾部里的日期字节，不破坏稳定前缀。
 *
 * The prompt is assembled once per send, but an agent loop can keep issuing
 * requests for hours (and a queued prompt is drained with the prompt from the
 * earlier send), so the injected `- Current date:` could be days old — the
 * reported "session thinks today is 11-09" drift. Replacing just the date
 * keeps the byte-stable head untouched: the runtime context lives after
 * [ChatViewModel.systemPromptStablePrefixLen], in the uncached dynamic tail.
 *
 * Pure and deliberately narrow: no marker (a provider-specific prompt) or a
 * blank/None prompt is returned unchanged, and only the runtime-context date
 * is rewritten.
 *
 * [GH#563] The marker is searched with `lastIndexOf`, not `indexOf`: an
 * earlier occurrence can come from recalled memory, a task anchor or the
 * SOUL body — and the StandingContract re-assertion appended after the
 * runtime context contains no date line, so the last match is the real one.
 */
internal fun refreshRuntimeContextDate(prompt: String?, dateStr: String): String? {
    val base = prompt ?: return null
    if (base.isEmpty() || dateStr.isBlank()) return prompt
    val marker = "- Current date: "
    val markerAt = base.lastIndexOf(marker)
    if (markerAt < 0) return prompt
    val valueStart = markerAt + marker.length
    val valueEnd = base.indexOf(' ', valueStart)
    if (valueEnd < 0) return prompt
    if (base.substring(valueStart, valueEnd) == dateStr) return prompt
    return base.substring(0, valueStart) + dateStr + base.substring(valueEnd)
}

/**
 * The per-day suffix. Field order is part of the cache contract (date → tz →
 * lang → model count): reordering changes bytes after the stable prefix for no
    * benefit.
 */
internal fun renderRuntimeContext(
    dateStr: String,
    tzId: String,
    lang: String,
    modelUseCount: Int,
): String =
    "\n\nRuntime context:\n" +
        "- Current date: $dateStr ($tzId)\n" +
        "- Device language: $lang\n" +
        "- minis-model-use models available: $modelUseCount"

/**
 * [T-parallel-tool-calls] The parallel-call capability declaration, built from
 * the tools THIS request actually carries.
 *
 * Why dynamic: the old prompt hardcoded tool names, and the announced-tool gate
 * (check_announced_tools.py) rejects any name the core schema hides — a
 * disabled memory feature (`memory_get`), a turned-off web search, or a
 * long-tail tool not yet discovered by `find_tools` made the prompt announce a
 * tool the model could not call. Filtering the live schema keeps the claim true
 * and the gate green by construction.
 *
 * Returns null when fewer than two parallel-safe tools are present: a batching
 * claim with nothing to batch is noise.
 */
internal fun parallelToolCallsPrompt(tools: List<AgentToolDefinition>): String? {
    if (tools.isEmpty()) return null
    val parallel = tools.map { it.name }
        .filter { com.openminis.app.tools.ToolCallLanes.laneFor(it) == com.openminis.app.tools.ToolCallLane.CONCURRENT }
    if (parallel.size < 2) return null
    return buildString {
        append("Parallel tool calls (capability, not advice):\n")
        append("- The host CAN run several independent calls from ONE response concurrently. ")
        append("Use it for recon: when you need N unrelated reads, return them together in one ")
        append("response instead of one per turn.\n")
        append("- Parallel-safe this turn: ").append(parallel.joinToString(", ")).append(".\n")
        append("- Write/stateful calls are serial barriers: never batch one with a call that must ")
        append("observe its effect. Read-only calls have no side effects, so they may overlap freely.\n")
        append("- Your emission order IS your dependency declaration. If step B needs step A's ")
        append("output, do not emit them together — wait for A's result in the next turn.")
    }
}
