package com.openminis.app.ui.chat

/**
 * [T-token-scan-memo] Token estimation re-scans every character of every
 * historical message on each call — send-time gates, in-loop policy checks, and
 * progressive-trim level decisions all walk the full history — and the ToolUse
 * branch calls `input.toString()` per part, a linear copy of multi-KB JSON
 * arguments (the real dominant cost on long tool-heavy sessions).
 *
 * Why identity, not equality
 * --------------------------
 * The previous implementation used `WeakHashMap<String, Long>`. That looks safe
 * — String equality is content equality, so two instances with the same text hit
 * the same entry — but it makes every lookup hash the WHOLE string. On a
 * 700K-character session the estimator hashes hundreds of kilobytes per message
 * just to discover the entry it already had. The scan it was trying to avoid is
 * O(n); the hash it pays instead is also O(n), plus a global `synchronizedMap`
 * monitor on every get and put.
 *
 * Both inputs are structurally immutable for their lifetime: messages are data
 * classes, a streamed delta appends a NEW String instance, and tool args settle
 * as a NEW JSONObject when the call completes. So per-REFERENCE memoization is
 * correct — the same reference always scans to the same value — and
 * `IdentityHashMap` gets there in O(1) without hashing the payload.
 *
 * Bounded
 * -------
 * A plain IdentityHashMap would grow with history and leak: it strongly
 * references keys, so dropping a message from history would not free its entry.
 * The map is capped at [MAX_ENTRIES]; when the cap is hit the whole map is
 * cleared (a cheap generational reset — refilling costs one scan per live
 * message, which is exactly the work the cache exists to avoid doing twice, and
 * the clear happens once per few thousand messages rather than per call).
 */
internal object TokenScanMemo {

    /** Entries kept before the cache is reset wholesale. */
    private const val MAX_ENTRIES = 4_096

    private val toolUseJson =
        java.util.IdentityHashMap<Any, String>(256)

    private val textScans =
        java.util.IdentityHashMap<String, Long>(1_024)

    /** Memoizes `input.toString()` per object — multi-KB JSON args copied once. */
    internal fun toolUseInputJson(input: Any): String {
        synchronized(toolUseJson) {
            toolUseJson[input]?.let { return it }
        }
        val json = input.toString()
        synchronized(toolUseJson) {
            if (toolUseJson.size >= MAX_ENTRIES) toolUseJson.clear()
            toolUseJson[input] = json
        }
        return json
    }

    /** Memoizes the ASCII/CJK token scan per text REFERENCE (no payload hashing). */
    internal fun mixedTokens(text: String): Long {
        synchronized(textScans) {
            textScans[text]?.let { return it }
        }
        var ascii = 0
        var other = 0
        for (c in text) if (c.code < 128) ascii++ else other++
        val total = ascii / 4L + other * 3L / 5L
        synchronized(textScans) {
            if (textScans.size >= MAX_ENTRIES) textScans.clear()
            textScans[text] = total
        }
        return total
    }
}
