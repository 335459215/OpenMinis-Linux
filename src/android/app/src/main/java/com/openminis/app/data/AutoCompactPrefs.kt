package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App-level persisted "auto-compact on threshold" toggle.
 *
 * Mirrors iOS `AIChatViewModel.autoCompactEnabled`, whose backing store is the
 * UserDefaults key `autoCompactOnThreshold` (AIChatViewModel.swift:853). The
 * key name is reused verbatim so the two platforms stay greppable as one
 * feature.
 *
 * Semantics:
 *   - true (the default): crossing the compact threshold before a send
 *     compacts silently and then sends.
 *   - false: the same crossing PROMPTS the user.
 * Threshold percent defaults to 75 (compact while there is still headroom;
 * the old 90% default effectively waited until the window was nearly full)
 * and is user-configurable.
 *
 * Global rather than per-session on purpose: iOS persists it so "future
 * conversations inherit it", which is the whole point of the one-tap opt-in
 * offered on the prompt.
 */
object AutoCompactPrefs {
    private const val PREFS = "minis_auto_compact_prefs"
    private const val KEY_ENABLED = "autoCompactOnThreshold"
    private const val KEY_THRESHOLD = "autoCompactThresholdPercent"
    private const val KEY_AUTO_NEW_SESSION_ENABLED = "autoNewSessionAfterCompaction"
    private const val KEY_AUTO_NEW_SESSION_THRESHOLD = "autoNewSessionCompactionThreshold"
    private const val KEY_PENDING_HANDOFF = "pendingHandoffSummary"
    const val DEFAULT_THRESHOLD_PERCENT = 75
    const val DEFAULT_AUTO_NEW_SESSION_THRESHOLD = 3

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = true

    @Volatile
    private var cachedThresholdPercent: Int = DEFAULT_THRESHOLD_PERCENT

    @Volatile
    private var cachedAutoNewSessionEnabled: Boolean = true

    @Volatile
    private var cachedAutoNewSessionThreshold: Int = DEFAULT_AUTO_NEW_SESSION_THRESHOLD

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Capture the app context and warm the cache. Called from MinisApp.onCreate,
     * so [isEnabled] is safe from call sites that have no Context — the same
     * arrangement [FastModePrefs] uses.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, true)
        cachedThresholdPercent = prefs(context).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD_PERCENT)
            .coerceIn(50, 95)
        cachedAutoNewSessionEnabled = prefs(context).getBoolean(KEY_AUTO_NEW_SESSION_ENABLED, true)
        cachedAutoNewSessionThreshold = prefs(context)
            .getInt(KEY_AUTO_NEW_SESSION_THRESHOLD, DEFAULT_AUTO_NEW_SESSION_THRESHOLD)
            .coerceIn(1, 3)
    }

    /** Context-free read. True before [prime] runs (auto-compact on by default). */
    fun isEnabled(): Boolean = cachedEnabled

    fun thresholdPercent(): Int = cachedThresholdPercent.coerceIn(50, 95)

    fun autoNewSessionEnabled(): Boolean = cachedAutoNewSessionEnabled

    fun autoNewSessionThreshold(): Int = cachedAutoNewSessionThreshold.coerceIn(1, 3)

    fun setAutoNewSessionEnabled(context: Context, enabled: Boolean) {
        cachedAutoNewSessionEnabled = enabled
        prefs(context).edit().putBoolean(KEY_AUTO_NEW_SESSION_ENABLED, enabled).apply()
    }

    fun setAutoNewSessionThreshold(context: Context, threshold: Int) {
        cachedAutoNewSessionThreshold = threshold.coerceIn(1, 3)
        prefs(context).edit().putInt(KEY_AUTO_NEW_SESSION_THRESHOLD, cachedAutoNewSessionThreshold).apply()
    }

    /**
     * Records one successful compaction and returns true when this session has
     * reached the configured limit. Counters are per-session and survive
     * process restarts; they intentionally live outside the chat DB so no
     * schema migration is needed.
     */
    fun recordCompaction(sessionId: String): Boolean {
        val ctx = appContext ?: return false
        if (!cachedAutoNewSessionEnabled || sessionId.isBlank()) return false
        val key = "autoNewSessionCount:$sessionId"
        val count = prefs(ctx).getInt(key, 0) + 1
        prefs(ctx).edit().putInt(key, count).apply()
        return count >= cachedAutoNewSessionThreshold
    }

    /** Stores the latest compact summary so the next draft can continue the task. */
    fun saveHandoffSummary(summary: String) {
        val ctx = appContext ?: return
        prefs(ctx).edit().putString(KEY_PENDING_HANDOFF, summary).apply()
    }

    fun consumeHandoffSummary(): String? {
        val ctx = appContext ?: return null
        val value = prefs(ctx).getString(KEY_PENDING_HANDOFF, null)?.trim().orEmpty()
        if (value.isEmpty()) return null
        prefs(ctx).edit().remove(KEY_PENDING_HANDOFF).apply()
        return value
    }

    fun setThresholdPercent(percent: Int) {
        cachedThresholdPercent = percent.coerceIn(50, 95)
        appContext?.let {
            prefs(it).edit().putInt(KEY_THRESHOLD, cachedThresholdPercent).apply()
        }
    }

    fun setThresholdPercent(context: Context, percent: Int) {
        cachedThresholdPercent = percent.coerceIn(50, 95)
        prefs(context).edit().putInt(KEY_THRESHOLD, cachedThresholdPercent).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Context-free write for the in-chat one-tap opt-in ("Compact & always
     * auto-compact"), which runs from the ViewModel where no Activity context
     * is at hand. No-ops the persist step if [prime] never ran, but still
     * updates the cache so the current process behaves as asked.
     */
    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        appContext?.let { prefs(it).edit().putBoolean(KEY_ENABLED, enabled).apply() }
    }
}
