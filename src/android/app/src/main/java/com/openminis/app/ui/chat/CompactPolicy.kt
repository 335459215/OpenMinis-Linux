package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage

/**
 * [T-android-compact-policy] Compaction budget arithmetic and the summary
 * quality gate, extracted from ChatViewModel's companion to keep that file
 * under its architecture ceiling. Call sites and tests keep using the
 * ChatViewModel aliases, so the policy has one home and no duplicated
 * constants.
 */
internal object CompactPolicy {

    /** Floor for a compaction's leaf-call budget. */
    const val MAX_COMPACT_LLM_CALLS = 6

    /** Ceiling for the dynamic leaf-call budget; still bounds the worst case. */
    const val MAX_COMPACT_LLM_CALLS_CEILING = 24

    const val COMPACT_SEGMENT_MIN_TOKENS = 8_000

    /**
     * Share of the model window a single summary request may occupy. This is
     * a RATIO, not a number: 200K -> 150K, 1M -> 750K.
     */
    const val COMPACT_SEGMENT_WINDOW_NUMERATOR = 3
    const val COMPACT_SEGMENT_WINDOW_DENOMINATOR = 4

    /** Room kept for the summary text and the system prompt. */
    const val COMPACT_SEGMENT_OUTPUT_RESERVE_TOKENS = 16_000

    /** Leaves that may be in flight at once. */
    const val COMPACT_LEAF_CONCURRENCY = 2

    const val COMPACT_SPLIT_DEPTH_FLOOR = 3

    /** 2^5 = 32 leaves, enough for ~1.5M tokens at the segment budget. */
    const val COMPACT_SPLIT_DEPTH_CEILING = 5

    const val COMPACT_TIMEOUT_BASE_MS = 90_000L
    const val COMPACT_TIMEOUT_PER_10K_CHARS_MS = 15_000L
    const val COMPACT_TIMEOUT_MAX_MS = 540_000L

    const val MIN_ACCEPTABLE_SUMMARY_CHARS = 120

    /**
     * CJK-aware token estimate. `length / 4` under-counts Chinese by 3-4x,
     * which kept the proactive split from firing on Chinese-heavy sessions.
     */
    fun estimateCompactTokens(text: String): Int {
        var cjk = 0
        for (ch in text) {
            val c = ch.code
            if (c in 0x2E80..0x9FFF || c in 0xF900..0xFAFF ||
                c in 0x3000..0x303F || c in 0xFF00..0xFFEF
            ) {
                cjk++
            }
        }
        val other = text.length - cjk
        return (cjk + other / 4).coerceAtLeast(1)
    }

    fun compactSegmentTokenBudget(contextWindow: Int): Int {
        val window = contextWindow.coerceAtLeast(1)
        val byRatio = window * COMPACT_SEGMENT_WINDOW_NUMERATOR / COMPACT_SEGMENT_WINDOW_DENOMINATOR
        val roomForOutput = window - COMPACT_SEGMENT_OUTPUT_RESERVE_TOKENS
        return minOf(byRatio, roomForOutput).coerceAtLeast(COMPACT_SEGMENT_MIN_TOKENS)
    }

    /** Leaf calls needed to cover [estimatedTokens] at [tokenBudget], plus one rescue. */
    fun compactCallBudgetFor(estimatedTokens: Int, tokenBudget: Int): Int {
        if (tokenBudget <= 0) return MAX_COMPACT_LLM_CALLS
        val segments = (estimatedTokens + tokenBudget - 1) / tokenBudget
        return (segments + 2).coerceIn(MAX_COMPACT_LLM_CALLS, MAX_COMPACT_LLM_CALLS_CEILING)
    }

    /** Split depth that yields at least one leaf per segment, within the ceiling. */
    fun compactMaxDepthFor(estimatedTokens: Int, tokenBudget: Int): Int {
        if (tokenBudget <= 0) return COMPACT_SPLIT_DEPTH_FLOOR
        val segments = (estimatedTokens + tokenBudget - 1) / tokenBudget
        var depth = 0
        var leaves = 1
        while (leaves < segments && depth < COMPACT_SPLIT_DEPTH_CEILING) {
            leaves = leaves shl 1
            depth++
        }
        return depth.coerceAtLeast(COMPACT_SPLIT_DEPTH_FLOOR)
    }

    fun shouldProactivelySplit(
        messageCount: Int,
        estimatedTokens: Int,
        tokenBudget: Int,
        depth: Int,
        callsAlreadySpent: Int,
        callBudget: Int = MAX_COMPACT_LLM_CALLS,
        maxDepth: Int = COMPACT_SPLIT_DEPTH_FLOOR,
    ): Boolean =
        messageCount >= 2 &&
            depth < maxDepth &&
            estimatedTokens > tokenBudget &&
            callsAlreadySpent + 2 <= callBudget

    /** Wall-clock budget for compacting a transcript of [transcriptChars]. */
    fun compactTimeoutMsFor(transcriptChars: Int): Long {
        val growth = (transcriptChars / 10_000L) * COMPACT_TIMEOUT_PER_10K_CHARS_MS
        return (COMPACT_TIMEOUT_BASE_MS + growth).coerceAtMost(COMPACT_TIMEOUT_MAX_MS)
    }

    /**
     * A summary is accepted when it is long enough to carry state AND echoes
     * something from the newest real user turn. Tool output arrives as a USER
     * message ("[Tool result: ...]"); using it as the probe demanded that the
     * summary echo a build log, which no faithful summary does.
     */
    fun isCompactSummaryAcceptable(summary: String, messages: List<LLMMessage>): Boolean {
        val trimmed = summary.trim()
        if (trimmed.length < MIN_ACCEPTABLE_SUMMARY_CHARS) return false
        val lastUser = messages.lastOrNull {
            it.role == LLMMessage.Role.USER &&
                !it.content.trimStart().startsWith("[Tool result:")
        } ?: messages.lastOrNull { it.role == LLMMessage.Role.USER } ?: return true
        val probe = lastUser.content.trim()
        if (probe.length < 4) return true
        val lower = trimmed.lowercase()
        val terms = ChatViewModel.summaryQueryTerms(probe).filter { t ->
            t.length >= 4 || (t.first().code in 0x4E00..0x9FFF)
        }
        if (terms.isEmpty()) return true
        return terms.any { t -> t in lower }
    }
}
