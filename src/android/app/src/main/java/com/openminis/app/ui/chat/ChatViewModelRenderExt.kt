package com.openminis.app.ui.chat

/**
 * Display-side transforms for the flat row -> ChatMessage conversion.
 *
 * Extracted from ChatViewModel.kt to keep that file under the architecture
 * ceiling. Everything here is pure: given persisted row fields, produce what
 * the UI should render. No ViewModel state is read.
 *
 * NOTE ON THE ROOM BAN: this file takes `role` / `partsJson` as primitives
 * instead of `MessageEntity` on purpose. The architecture policy forbids the
 * ui package from touching Room entities directly, and a new file cannot
 * inherit that pre-existing debt — only ChatViewModel.kt sits on the frozen
 * baseline. Passing the two columns keeps the transform here without opening a
 * new hole.
 *
 * [stripSystemReminders] and [stripAttachedFilesXml] are applied ONLY at this
 * UI-render boundary. agentHistory and the raw DB rows keep the untouched text
 * so the model keeps seeing reminders and the `<user-attached-files>` inventory
 * across retry/reload (iOS parity) — the file chips are rebuilt from the
 * mediaRef parts instead.
 */

/** Matches a whole `<system-reminder>` block plus its surrounding whitespace. */
private val systemReminderRegex =
    Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

internal fun stripSystemReminders(text: String): String =
    if (!text.contains("<system-reminder>")) text
    else systemReminderRegex.replace(text, "")

/**
 * [T-android-retry-attachment-loss] Remove the `<user-attached-files>` XML
 * inventory from a persisted text part for DISPLAY only. Mirrors the
 * index-based strip already used by editMessage / the title-fallback path: if
 * the closing tag is missing (truncated row) everything from the opening tag
 * onward is dropped, so a half-written inventory never leaks into the bubble.
 */
internal fun stripAttachedFilesXml(text: String): String {
    val startIdx = text.indexOf("<user-attached-files>")
    if (startIdx < 0) return text
    val endTag = "</user-attached-files>"
    val endIdx = text.indexOf(endTag, startIdx)
    return if (endIdx >= 0) {
        text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
    } else {
        text.substring(0, startIdx)
    }
}

/**
 * Collect toolResult payloads keyed by toolUseId from the user-role rows that
 * carry them. `runCatching` swallows malformed JSON per row on purpose: a
 * single corrupt partsJson blob must not blank out the entire transcript.
 *
 * Takes `(role, partsJson)` pairs — see the Room note in the file header.
 */
internal fun buildToolResultMap(rows: List<Pair<String, String>>): Map<String, ToolResultData> {
    val result = mutableMapOf<String, ToolResultData>()
    for ((role, partsJson) in rows) {
        if (role != "user") continue
        runCatching {
            val array = org.json.JSONArray(partsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("type") != "toolResult") continue
                val value = obj.getJSONObject("value")
                val toolUseId = value.optString("toolUseId", "")
                if (toolUseId.isNotEmpty()) {
                    result[toolUseId] = ToolResultData(
                        output = value.optString("output", ""),
                        success = value.optBoolean("success", true),
                    )
                }
            }
        }
    }
    return result
}
