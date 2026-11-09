package com.openminis.app.ui.chat

/**
 * JSON escaping for the tool-part serializers.
 *
 * Extracted from ChatViewModel.kt to keep that file under the architecture
 * ceiling: the serializers in [ChatViewModelAttachmentExt] and the tool-result
 * builders call this on every text/toolUse/toolResult part, so it has to stay
 * `internal` (same module) rather than private.
 *
 * Escapes exactly what JSON requires — the two quote characters, the three
 * short control escapes, and \\uXXXX for the remaining C0 range. Anything above
 * 0x1F is passed through untouched, so UTF-8 payloads (CJK prompts, emoji)
 * keep their original bytes instead of being \\u-escaped into unreadable soup.
 */
internal fun escapeJson(text: String): String {
    val sb = StringBuilder("\"")
    for (c in text) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                else sb.append(c)
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

/**
 * One toolResult payload flattened out of a persisted `partsJson` array.
 *
 * Lives next to [escapeJson] because both are pure serialization helpers, and
 * because [ToolResultData] is only referenced by the toChatMessages transform
 * that was extracted alongside it.
 */
internal data class ToolResultData(val output: String, val success: Boolean)
