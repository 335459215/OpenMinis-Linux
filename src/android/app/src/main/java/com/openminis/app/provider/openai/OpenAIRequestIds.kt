package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-openai-request-bodies-split] Id / cache-key / tool-shape helpers that
 * used to be private members of [OpenAIRequestBodies]. Extracted when that
 * file outgrew its architecture-policy ceiling: the id-capping family and the
 * Responses-API tool shape are pure functions over the request model, with no
 * dependency on the provider instance state, so they belong at package level.
 *
 * Every helper here is `internal` and lives in the same package as the caller,
 * so no import churn is needed on either side.
 */

/** Splits the Responses-API combined id `call_…|fc_…` into its two halves. */
internal fun splitResponsesAPIIds(combined: String): Pair<String, String?> {
    val sep = combined.indexOf('|')
    return if (sep < 0) combined to null
    else combined.substring(0, sep) to combined.substring(sep + 1)
}

/** Responses-API ids must be ≤64 chars; truncate defensively to avoid 400s. */
internal fun capResponsesId(id: String): String =
    if (id.length <= 64) id else id.substring(0, 64)

/**
 * [T-android-tool-call-id-too-long] Chat-Completions `tool_calls[].id` /
 * `tool_call_id` must be ≤64 chars — OpenAI-compatible endpoints reject longer
 * ids with a 400 ("string too long. Expected a string with maximum length 64").
 * Two ways an id gets over 64 here:
 *   - a Responses-origin history turn stores the combined "call_…|fc_…" id
 *     (splitResponsesAPIIds' form) which is replayed verbatim on a Chat
 *     Completions request;
 *   - memory-tool / synthetic ids that are long by construction.
 * We keep only the call_-id half (before any '|') and, if still >64, replace
 * it with a deterministic SHA-256-derived id. Determinism matters: the SAME
 * raw id must map to the SAME capped id so the assistant tool_call and its
 * matching tool result still pair up (a mismatch is its own 400). The
 * downstream dedupe pass then guarantees uniqueness within the request.
 */
internal fun capChatToolCallId(id: String): String {
    val callHalf = id.substringBefore('|')
    if (callHalf.length <= 64) return callHalf
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(callHalf.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    // "call_" + 56 hex chars = 61 chars, safely under 64 and clearly a call id.
    return "call_${hex.take(56)}"
}

/**
 * Derives a stable per-conversation `prompt_cache_key` for the Responses
 * API. Mirrors iOS derivePromptCacheKey (OpenAIAgentProvider.swift:515).
 * The first user message is re-sent verbatim on every turn → its hash is
 * stable across turns within the same chat, distinct between chats.
 * Falls back to a random UUID when there is no user text yet (first
 * turn with attachments-only input, etc.).
 */
internal fun derivePromptCacheKey(messages: List<LLMMessage>): String {
    for (msg in messages) {
        if (msg.role != LLMMessage.Role.USER) continue
        val text = msg.contentParts
            .filterIsInstance<AgentContentPart.Text>()
            .joinToString("") { it.text }
            .ifEmpty { msg.content }
        if (text.isNotEmpty()) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "minis-${hex.take(32)}"
        }
    }
    return "minis-${java.util.UUID.randomUUID().toString().lowercase()}"
}

// [T-thinking-ultra-declared][T-thinking-ultra-manual] The former private
// mapThinkingLevelToResponsesEffort() retired into
// ThinkingEffort.effortOrDeclaredUltra — one shared mapping, so a
// declared or manually-asserted "ultra" cannot fold on one path only.

/**
 * Responses-API tool shape — flat {type, name, description, parameters},
 * NOT the Chat Completions wrapper {type, function:{...}}. Mirrors iOS
 * convertToolsResponsesAPI (OpenAIAgentProvider.swift:977).
 */
internal fun AgentToolDefinition.toResponsesAPIJson(): JSONObject {
    val props = JSONObject()
    for ((key, param) in parameters) {
        props.put(key, param.toJson())
    }
    val params = JSONObject().apply {
        put("type", "object")
        put("properties", props)
        if (required.isNotEmpty()) put("required", JSONArray(required))
    }
    return JSONObject().apply {
        put("type", "function")
        put("name", name)
        put("description", description)
        put("parameters", params)
    }
}
