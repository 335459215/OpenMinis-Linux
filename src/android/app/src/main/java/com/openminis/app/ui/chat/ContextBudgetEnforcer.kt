package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger

/**
 * [T-context-budget-enforcer] The missing guarantee: **after this runs, the
 * outgoing payload is below the threshold that triggers compaction.**
 *
 * Why this exists
 * ---------------
 * `ProgressiveTrim` reduces bulk by *decorating* old messages (strip reasoning,
 * stub large tool outputs) but it never removes a message, and it hard-protects
 * the last `PROTECTED_RECENT_MESSAGES` entries plus the whole current round. On a
 * tool-heavy session (RustFS / build logs / large file reads) the live round
 * alone can be several hundred thousand tokens, so the projection stays above
 * the compact threshold forever no matter how many times it compacts.
 *
 * The observed failure: the session sits at 751451 / 1000000 (75.1%), which is
 * *above* the 75% compact threshold but far below the real window ceiling.
 * Every compaction folds history that is already folded, the marker's anchor
 * does not advance, the guard asks again, and the turn either stops or burns its
 * whole in-loop compaction budget on no-ops. Compaction cannot recover because
 * the bulk is NOT in the foldable range.
 *
 * So the target is the COMPACT threshold (minus a margin for oscillation), not
 * some fixed fraction of the window: "over the compact line" is exactly the
 * condition that produces the loop.
 *
 * Two ordered passes, cheapest and least destructive first:
 *
 *   A. **Stub old tool results**, oldest first. Recoverable — the stub states how
 *      many characters were dropped and the original text is still in the
 *      session DB / workspace spill.
 *   B. **Drop whole old turns** at USER boundaries, oldest first — only when a
 *      `<context-summary>` carrier exists, so the dropped turns are still
 *      represented in the prompt. Without a summary this pass is skipped: it
 *      would silently delete conversation the user can still see, which is worse
 *      than being over budget.
 *
 * Never dropped: the summary carrier (the only record of the folded turns) and
 * the last [KEEP_TAIL_USER_TURNS] user turns — pass B cuts only below them.
 * Pass A may still STUB a tool result inside that tail (keeping the newest
 * [KEEP_RECENT_TOOL_RESULTS] verbatim): on a tool-heavy live round the bulk IS
 * the current round, and refusing to touch it is exactly why the session could
 * never get under the line. Stubbing is recoverable; deleting a turn is not,
 * so the two passes have different boundaries on purpose.
 *
 * Cost model
 * ----------
 * Per-message costs are computed ONCE into an array; planning works off prefix
 * sums. A naive "re-estimate after every change" loop is O(n²) over a
 * 700K-character transcript and would itself become the lag the user reports.
 *
 * Calibration
 * -----------
 * The caller's gate measures with `estimateMessagesTokens()`, which multiplies by
 * a per-model `TokenUsageCalibration` factor (1.0 … 8.0). Planning here uses raw
 * per-message costs, so every raw value is multiplied by the same scale before
 * being compared to the ceiling — otherwise the enforcer would think a payload
 * is smaller than the gate does and never fire.
 */
internal object ContextBudgetEnforcer {

    /** Tool results kept verbatim at the tail. */
    internal const val KEEP_RECENT_TOOL_RESULTS: Int = 3

    /** Head kept verbatim when a tool result is stubbed. */
    internal const val STUB_HEAD_CHARS: Int = 400

    /** Tool results shorter than this are not worth stubbing. */
    private const val STUB_MIN_CHARS: Int = 1_500

    /** User turns always preserved verbatim at the tail. */
    internal const val KEEP_TAIL_USER_TURNS: Int = 2

    /** Hard ceiling on the target, so a 95% preference cannot starve headroom. */
    private const val MAX_TARGET_FRACTION: Double = 0.90

    /** Same ASCII/CJK heuristic the estimator uses elsewhere (raw, uncalibrated). */
    internal fun estimateTokens(messages: List<LLMMessage>): Long {
        var total = 0L
        for (msg in messages) total += costOf(msg)
        return total
    }

    private fun costOf(msg: LLMMessage): Long {
        var total = mixed(msg.content)
        for (part in msg.contentParts) {
            total += when (part) {
                is AgentContentPart.Text -> mixed(part.text)
                is AgentContentPart.ToolUse -> mixed(part.input.toString())
                is AgentContentPart.ToolResult -> mixed(part.content)
                is AgentContentPart.ImageData -> 1_500L
            }
        }
        return total
    }

    private fun mixed(text: String): Long {
        if (text.isEmpty()) return 0L
        var ascii = 0L
        var other = 0L
        for (c in text) if (c.code < 128) ascii++ else other++
        return ascii / 4L + other * 3L / 5L
    }

    private fun isSummaryCarrier(msg: LLMMessage): Boolean =
        msg.role == LLMMessage.Role.USER && msg.content.contains("<context-summary>")

    /**
     * @param messages         the projection about to be sent.
     * @param windowTokens     the model's context window.
     * @param targetFraction   fraction of the window the payload must fit in —
     *                         pass the effective compact threshold minus a margin.
     * @param calibratedTokens the gate's own measurement of [messages]
     *                         (`estimateMessagesTokens`), or <= 0 to measure raw.
     * @return the same instance when nothing had to change (callers can
     *         identity-compare), otherwise a new list.
     */
    fun enforce(
        messages: List<LLMMessage>,
        windowTokens: Int,
        targetFraction: Double,
        calibratedTokens: Int = 0,
    ): List<LLMMessage> {
        if (windowTokens <= 0 || messages.isEmpty()) return messages
        val fraction = targetFraction.coerceIn(0.30, MAX_TARGET_FRACTION)
        val ceiling = (windowTokens * fraction).toLong()

        val rawTotal = estimateTokens(messages)
        // Same scale the gate applies; 1.0 when uncalibrated.
        val scale = if (calibratedTokens > 0 && rawTotal > 0) {
            (calibratedTokens.toDouble() / rawTotal.toDouble()).coerceAtLeast(1.0)
        } else {
            1.0
        }
        if ((rawTotal * scale) <= ceiling) return messages

        // One cost pass for the whole enforcement — the per-message cost is
        // recomputed never. (A naive implementation re-walks the transcript
        // after every candidate, which on a 700K-char session is the lag the
        // user reports.)
        val n = messages.size
        val cost = LongArray(n) { costOf(messages[it]) }

        // The summary carrier can sit ANYWHERE in the projection: v2 inlines it
        // into the first user message after the anchor, so it is not messages[0].
        val summaryIdx = messages.indexOfFirst { isSummaryCarrier(it) }
        val protectHead = if (summaryIdx >= 0) summaryIdx + 1 else 0

        // Last KEEP_TAIL_USER_TURNS user turns (and everything after) are never
        // touched; clamp so it can never fall inside the protected head.
        val tailStart = protectedTailStart(messages).coerceAtLeast(protectHead)

        // ── Pass A: stub old tool results (recoverable) ──────────────────
        val stubbed = stubOldToolResults(messages, ceiling, scale, cost)
        if ((estimateTokens(stubbed) * scale) <= ceiling) return stubbed

        // ── Pass B: drop whole old turns (only when a summary covers them) ──
        if (summaryIdx < 0) {
            AppLogger.warning(
                ChatViewModel.TAG,
                "[ContextBudget] still over budget after stubbing and no <context-summary> " +
                    "exists — refusing to drop turns (that would delete visible history); " +
                    "compaction has to succeed for this session",
            )
            return stubbed
        }
        return dropOldTurns(stubbed, ceiling, scale, protectHead, tailStart)
    }

    /**
     * Stub the oldest tool results, keeping the newest
     * [KEEP_RECENT_TOOL_RESULTS] verbatim, stopping as soon as the projected
     * total fits.
     */
    private fun stubOldToolResults(
        messages: List<LLMMessage>,
        ceiling: Long,
        scale: Double,
        cost: LongArray,
    ): List<LLMMessage> {
        val candidates = ArrayList<Pair<String, Int>>()
        for (msg in messages) {
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolResult && part.content.length >= STUB_MIN_CHARS) {
                    candidates.add(part.id to part.content.length)
                }
            }
        }
        if (candidates.size <= KEEP_RECENT_TOOL_RESULTS) return messages
        val droppable = candidates.dropLast(KEEP_RECENT_TOOL_RESULTS)

        var projected = (cost.sum() * scale).toLong()
        val stubIds = HashSet<String>()
        for ((id, len) in droppable) {
            if (projected <= ceiling) break
            // Approximate reclaim: dropped chars at the estimator's ~4 chars/token,
            // scaled the same way the gate scales. Exact value is recomputed by the
            // caller's next measurement; this only decides when to stop scanning.
            val saved = (((len - STUB_HEAD_CHARS) / 4.0) * scale).toLong()
            if (saved <= 0) continue
            stubIds.add(id)
            projected -= saved
        }
        if (stubIds.isEmpty()) return messages

        val out = ArrayList<LLMMessage>(messages.size)
        for (msg in messages) {
            val parts = msg.contentParts
            if (parts.isEmpty()) { out.add(msg); continue }
            var touched = false
            val rebuilt = parts.map { part ->
                if (part is AgentContentPart.ToolResult && part.id in stubIds &&
                    part.content.length >= STUB_MIN_CHARS
                ) {
                    touched = true
                    part.copy(content = stubText(part.content))
                } else {
                    part
                }
            }
            out.add(if (touched) msg.copy(contentParts = rebuilt) else msg)
        }
        AppLogger.info(
            ChatViewModel.TAG,
            "[ContextBudget] stubbed ${stubIds.size} tool result(s) " +
                "(ceiling=$ceiling scale=${"%.2f".format(scale)})",
        )
        return out
    }

    /**
     * Drop whole turns at USER boundaries, oldest first, inside
     * `[protectHead, tailStart)`. Cutting only at a USER message means a
     * `tool_use` is never orphaned from its `tool_result` — Anthropic rejects
     * that outright and the UI would render a dangling card.
     */
    private fun dropOldTurns(
        messages: List<LLMMessage>,
        ceiling: Long,
        scale: Double,
        protectHead: Int,
        tailStart: Int,
    ): List<LLMMessage> {
        val n = messages.size
        val cost = LongArray(n) { costOf(messages[it]) }
        val prefix = LongArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + cost[i]

        fun remaining(cut: Int): Long =
            ((prefix[n] - (prefix[cut] - prefix[protectHead])) * scale).toLong()

        var cut = protectHead
        while (cut < tailStart && remaining(cut) > ceiling) {
            var next = cut + 1
            while (next < tailStart && messages[next].role != LLMMessage.Role.USER) next++
            if (next >= tailStart) break
            cut = next
        }
        if (cut <= protectHead) return messages

        val kept = ArrayList<LLMMessage>(n - cut + protectHead)
        kept.addAll(messages.subList(0, protectHead))
        kept.addAll(messages.subList(cut, n))
        AppLogger.info(
            ChatViewModel.TAG,
            "[ContextBudget] dropped ${cut - protectHead} old message(s) at a user boundary " +
                "(ceiling=$ceiling left=${remaining(cut)})",
        )
        return kept
    }

    /**
     * Index of the user message that starts the protected tail (the last
     * [KEEP_TAIL_USER_TURNS] user turns).
     */
    private fun protectedTailStart(messages: List<LLMMessage>): Int {
        var seen = 0
        for (i in messages.indices.reversed()) {
            if (messages[i].role == LLMMessage.Role.USER) {
                seen++
                if (seen >= KEEP_TAIL_USER_TURNS) return i
            }
        }
        return 0
    }

    private fun stubText(content: String): String =
        content.take(STUB_HEAD_CHARS) +
            "\n… [trimmed by context-budget enforcer: ${content.length - STUB_HEAD_CHARS} chars dropped; " +
            "the full output is still in this session's DB / workspace spill]"
}
