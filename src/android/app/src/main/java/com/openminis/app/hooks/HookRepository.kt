package com.openminis.app.hooks

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * One external turn hook. Hooks are local-only by construction: the URL must
 * point at loopback. That keeps memory / prompt plugins on the same device and
 * stops a stray config from exfiltrating chat context.
 */
internal data class HookEndpoint(
    val id: String,
    val url: String,
    val events: Set<String>,
    val timeoutMs: Long,
    val budgetChars: Int,
    val note: String = "",
) {
    fun supports(event: String): Boolean = event in events
}

/**
 * Reads hook configs from `minis-global/mcp-servers/hooks.json`, falling back
 * to entries in `servers.json` that carry a `hook` object:
 *
 * ```json
 * "minis_memory": {
 *   "command": "python3",
 *   "args": ["-u", "/var/minis/shared/minis-memory/mcp_server.py"],
 *   "hook": {
 *     "enabled": true,
 *     "url": "http://127.0.0.1:18903",
 *     "events": ["before_prompt", "after_turn"],
 *     "timeout_ms": 800
 *   }
 * }
 * ```
 *
 * Config is hot-reloaded by mtime; a broken file degrades to "no hooks".
 */
internal class HookRepository(private val context: Context) {
    companion object {
        private const val TAG = "HookRepository"
        private val DEFAULT_EVENTS = setOf("before_prompt", "after_turn")
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")
    }

    private val mcpDir: File
        get() = File(File(context.filesDir, "minis-global"), "mcp-servers")

    private val hooksFile: File
        get() = File(mcpDir, "hooks.json")

    private val serversFile: File
        get() = File(mcpDir, "servers.json")

    private val lock = Any()

    @Volatile
    private var cachedSignature: String = ""

    @Volatile
    private var cached: List<HookEndpoint> = emptyList()

    fun all(): List<HookEndpoint> {
        val before = signature()
        if (before == cachedSignature) return cached
        return synchronized(lock) {
            val after = signature()
            if (after == cachedSignature) {
                cached
            } else {
                val loaded = runCatching { load() }
                    .onFailure { Log.w(TAG, "load hooks failed: ${it.message}") }
                    .getOrDefault(emptyList())
                cached = loaded
                cachedSignature = after
                loaded
            }
        }
    }

    fun forEvent(event: String): List<HookEndpoint> = all().filter { it.supports(event) }

    fun hasEvent(event: String): Boolean = all().any { it.supports(event) }

    fun hasAny(): Boolean = all().isNotEmpty()

    private fun signature(): String = listOf(hooksFile, serversFile).joinToString("|") { file ->
        if (file.exists()) "${file.name}:${file.lastModified()}:${file.length()}" else "${file.name}:-"
    }

    private fun load(): List<HookEndpoint> {
        if (hooksFile.exists()) {
            loadHooksFile(hooksFile)?.let { return it }
        }
        return loadServersFile()
    }

    private fun loadHooksFile(file: File): List<HookEndpoint>? {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val hooks = root.optJSONObject("hooks") ?: return null
        val out = mutableListOf<HookEndpoint>()
        val keys = hooks.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val obj = hooks.optJSONObject(id) ?: continue
            parseEndpoint(id, obj)?.let(out::add)
        }
        return out
    }

    private fun loadServersFile(): List<HookEndpoint> {
        if (!serversFile.exists()) return emptyList()
        val root = JSONObject(serversFile.readText(Charsets.UTF_8))
        val servers = root.optJSONObject("mcpServers") ?: return emptyList()
        val out = mutableListOf<HookEndpoint>()
        val keys = servers.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val server = servers.optJSONObject(id) ?: continue
            if (!server.optBoolean("enabled", true)) continue
            val hook = server.optJSONObject("hook") ?: continue
            parseEndpoint(id, hook)?.let(out::add)
        }
        return out
    }

    private fun parseEndpoint(id: String, obj: JSONObject): HookEndpoint? {
        if (!obj.optBoolean("enabled", true)) return null
        val url = obj.optString("url", "").trim().trimEnd('/')
        if (!isLoopbackUrl(url)) {
            Log.w(TAG, "ignoring non-loopback hook $id: $url")
            return null
        }
        return HookEndpoint(
            id = id,
            url = url,
            events = parseEvents(obj.opt("events")),
            timeoutMs = obj.optLong("timeout_ms", obj.optLong("timeoutMs", 800L))
                .coerceIn(100L, 3000L),
            budgetChars = obj.optInt("budget_chars", obj.optInt("budgetChars", 1400))
                .coerceIn(200, 4000),
            note = obj.optString("note", ""),
        )
    }

    private fun parseEvents(value: Any?): Set<String> {
        val out = linkedSetOf<String>()
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) {
                val event = value.optString(index, "").trim()
                if (event.isNotEmpty()) out += event
            }
            is String -> value.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(out::add)
        }
        return if (out.isEmpty()) DEFAULT_EVENTS else out
    }

    private fun isLoopbackUrl(raw: String): Boolean {
        if (raw.isBlank()) return false
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            (scheme == "http" || scheme == "https") && host != null && host in LOOPBACK_HOSTS
        }.getOrDefault(false)
    }
}
