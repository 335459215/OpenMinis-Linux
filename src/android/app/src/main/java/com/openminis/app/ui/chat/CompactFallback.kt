package com.openminis.app.ui.chat

/**
 * [T-009] A compaction that produces nothing used to abort the whole operation:
 * the summary model returns an empty body (or the pass times out), and the
 * history keeps growing until the next request dies on context length. The
 * user sees "Compaction produced no output — try again later" and pressing it
 * again reproduces the same wall.
 *
 * This is the deterministic, model-free floor: a labelled digest of what was
 * being compacted, built from the transcript the summary call would have
 * received. It is deliberately dull — the point is that compaction ALWAYS
 * lands, so the session keeps working and the model summary can be retried
 * later on a smaller window.
 */
internal object CompactFallback {

    /**
     * Floor below which a digest carries no information worth writing. Must stay
     * well under the smallest useful header + two excerpts.
     */
    const val MIN_USEFUL_CHARS = 80

    /**
     * Build a digest from [transcript], keeping the opening and the most recent
     * material — the two ends a later turn actually needs to stay coherent.
     *
     * [header] is supplied by the caller as a string resource: the app ships 16
     * locales and a Kotlin literal here would (a) bypass them and (b) trip the
     * hardcoded-CJK ratchet.
     *
     * Deterministic: same input, same output. No model, no IO.
     */
    fun digest(
        transcript: String,
        messageCount: Int,
        header: String,
        budgetChars: Int = 1_200,
    ): String {
        val text = transcript.trim()
        if (text.isEmpty()) return ""
        if (text.length <= budgetChars) {
            return "$header\n（$messageCount）\n$text"
        }
        // Reserve room for header + counts before splitting the budget.
        val body = (budgetChars - header.length - 24).coerceAtLeast(MIN_USEFUL_CHARS)
        val head = (body * 0.4).toInt()
        val tail = body - head
        return buildString {
            appendLine(header)
            appendLine("[$messageCount msgs, ${text.length} chars — head/tail excerpt]")
            appendLine(text.take(head).trimEnd())
            appendLine("…[${text.length - head - tail} chars elided]…")
            append(text.takeLast(tail).trimStart())
        }
    }

    /**
     * Why a compaction pass ended without a summary. Kept as a closed set so
     * every branch has one message and no caller invents its own wording.
     */
    enum class Failure { Timeout, EmptyOutput, Cancelled, Error }

    /**
     * The digest is only worth writing when it says something: a transcript of
     * two short lines is not a summary, and writing it would make the compact
     * look successful while dropping real content.
     */
    fun usable(digest: String): Boolean = digest.trim().length >= MIN_USEFUL_CHARS
}
