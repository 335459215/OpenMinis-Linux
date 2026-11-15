package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart

/**
 * [T-arch-extract-context-usage] Context-usage measurement, extracted out of
 * `ChatViewModel.kt` so the architecture ratchet (a per-file line ceiling that
 * never rises) keeps holding. Behaviour is unchanged; the two functions were
 * moved verbatim.
 */

/**
 * max(real usage, estimate) so a stale-but-real reading always wins.
 *
 * [T-context-stale-reported] The provider-reported number describes the payload
 * of the LAST call and INCLUDES the system prompt + tool schemas, which are not
 * part of `agentHistory` — so a reported value somewhat above the history
 * estimate is normal, not stale. Only a grossly larger reading (1.5x) can be a
 * leftover from before a compaction shrank the projection; that is the case this
 * guard exists for.
 */
internal fun ChatViewModel.contextTokensForPolicy(): Int {
    val estimate = estimateAgentHistoryTokens()
    val reported = _lastTurnContextTokens.value
    return if (estimate > 0 && reported > 0 && reported > estimate * 1.5) {
        estimate
    } else {
        maxOf(reported, estimate)
    }
}

/**
 * Recompute contextUsage for the session-menu ring.
 *
 * [T-android-context-ring-zero] The char estimate walks the effective
 * (compacted) history — with a marker in play the raw list is not what the next
 * request carries ([T-android-compact-stale-usage]). A long session is
 * TAIL-PAGED (loadSessionTail), so a re-entered session showed ~0 tokens even
 * while the provider reported 23939/24688. Prefer the REAL provider-reported
 * context size from the last usage chunk; fall back to the char estimate only
 * before the first API call.
 *
 * [T-context-ring-no-full-walk] The provider-reported number is authoritative,
 * but the old body walked the ENTIRE effective history to build an estimate it
 * then threw away whenever `reported > 0`. On a 750K-token session that walk is
 * tens of milliseconds, and it runs on every `messages` emission
 * (ComposerContextGlow collects it) plus at every turn boundary — a measurable
 * slice of the "every reply is much slower now" regression. Short-circuit on the
 * real number.
 */
fun ChatViewModel.refreshContextUsage() {
    val reported = _lastTurnContextTokens.value
    val windowNow = currentModelContextWindow?.toLong() ?: 0L
    if (reported > 0) {
        contextUsage.value = com.openminis.app.data.model.ContextUsage(reported.toLong(), windowNow)
        return
    }
    var usedChars = 0L
    for (msg in effectiveAgentHistoryUncounted()) {
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.Text -> usedChars += part.text.length
                is AgentContentPart.ToolUse -> usedChars += TokenScanMemo.toolUseInputJson(part.input).length
                is AgentContentPart.ToolResult -> {
                    usedChars += part.content.length
                }
                is AgentContentPart.ImageData -> {}
            }
        }
    }
    val estTokens = (usedChars / 3.5).toLong()
    contextUsage.value = com.openminis.app.data.model.ContextUsage(estTokens, windowNow)
}
