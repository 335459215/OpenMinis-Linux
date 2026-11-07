package com.openminis.app.hooks

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class HookBeforePromptResult(
    val block: String,
    val endpoints: Int,
    val responded: Int,
)

/**
 * Local HTTP client for host hooks.
 *
 * The contract is deliberately small:
 *
 *   POST /hook/before_prompt
 *     { event, app, session_id, query, budget_chars }
 *     -> { ok, additional_context, meta }
 *
 *   POST /hook/after_turn
 *     { event, app, session_id, user_text, assistant_text }
 *     -> { ok, stored }
 *
 * All failures are fail-open: a hook may be down, slow, or broken and the chat
 * must keep working. That is why the timeouts are short and every call catches.
 */
internal class HookClient(
    private val repository: HookRepository,
) {
    companion object {
        private const val TAG = "HookClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_QUERY_CHARS = 4000
        private const val MAX_RESPONSE_CHARS = 16_000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .readTimeout(3_000, TimeUnit.MILLISECONDS)
        .writeTimeout(1_000, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var disabledUntilMs: Long = 0L

    private fun markFailure() {
        // A stopped plugin must not add 300ms to every turn. Retry after 30s.
        disabledUntilMs = System.currentTimeMillis() + 30_000L
    }

    suspend fun beforePrompt(
        sessionId: String,
        query: String,
        budgetChars: Int = 1400,
    ): HookBeforePromptResult = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() < disabledUntilMs) {
            return@withContext HookBeforePromptResult("", 0, 0)
        }
        val endpoints = repository.forEvent("before_prompt")
        if (endpoints.isEmpty() || query.isBlank()) {
            return@withContext HookBeforePromptResult("", endpoints.size, 0)
        }
        val blocks = mutableListOf<String>()
        var responded = 0
        for (endpoint in endpoints) {
            val body = JSONObject().apply {
                put("event", "before_prompt")
                put("app", "minis-ultra")
                put("version", 1)
                put("session_id", sessionId)
                put("query", query.take(MAX_QUERY_CHARS))
                put("budget_chars", minOf(budgetChars, endpoint.budgetChars))
                put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", query.take(MAX_QUERY_CHARS)),
                ))
            }
            val response = runCatching { post(endpoint, "/hook/before_prompt", body) }
                .onFailure {
                    Log.w(TAG, "${endpoint.id} before_prompt failed: ${it.message}")
                    markFailure()
                }
                .getOrNull()
                ?: continue
            responded += 1
            val text = extractContext(response)
            if (!text.isNullOrBlank()) {
                blocks += text.trim().take(endpoint.budgetChars)
            }
        }
        HookBeforePromptResult(
            block = blocks.joinToString("\n\n").take(maxOf(200, budgetChars)),
            endpoints = endpoints.size,
            responded = responded,
        )
    }

    suspend fun afterTurn(
        sessionId: String,
        userText: String,
        assistantText: String,
    ) = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() < disabledUntilMs) return@withContext
        val endpoints = repository.forEvent("after_turn")
        if (endpoints.isEmpty()) return@withContext
        val body = JSONObject().apply {
            put("event", "after_turn")
            put("app", "minis-ultra")
            put("version", 1)
            put("session_id", sessionId)
            put("user_text", userText.take(MAX_QUERY_CHARS))
            put("assistant_text", assistantText.take(MAX_QUERY_CHARS))
        }
        for (endpoint in endpoints) {
            runCatching { post(endpoint, "/hook/after_turn", body) }
                .onFailure {
                    Log.w(TAG, "${endpoint.id} after_turn failed: ${it.message}")
                    markFailure()
                }
        }
    }

    private fun post(endpoint: HookEndpoint, path: String, body: JSONObject): JSONObject? {
        val request = Request.Builder()
            .url(endpoint.url.trimEnd('/') + path)
            .post(body.toString().toRequestBody(JSON))
            .header("X-Minis-Hook", endpoint.id)
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) return@use null
            val text = response.body?.string() ?: return@use null
            if (text.length > MAX_RESPONSE_CHARS) return@use null
            runCatching { JSONObject(text) }.getOrNull()
        }
    }

    private fun extractContext(response: JSONObject): String? {
        val candidates = listOf(
            response.optString("additional_context", ""),
            response.optString("additionalContext", ""),
            response.optString("context", ""),
            response.optString("block", ""),
            response.optString("text", ""),
        )
        candidates.firstOrNull { it.isNotBlank() }?.let { return it }
        val data = response.optJSONObject("data") ?: return null
        return listOf(
            data.optString("additional_context", ""),
            data.optString("context", ""),
            data.optString("block", ""),
        ).firstOrNull { it.isNotBlank() }
    }
}
