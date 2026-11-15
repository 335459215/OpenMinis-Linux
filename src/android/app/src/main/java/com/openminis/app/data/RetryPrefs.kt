package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-retry-prefs] App-level, user-facing control for automatic retry.
 *
 * Why this exists
 * ---------------
 * Retry used to be hard-wired: `HttpRetryAfter.DEFAULT_MAX_RETRIES` (5) with the
 * fixed 1/2/4/8/16s ladder, and a per-model override buried in the model detail
 * screen. When the local Agent2API gateway drops (127.0.0.1:18901 refuses or
 * times out), the user has no way to say "just retry less", "retry longer", or
 * "don't retry at all, show me the error".
 *
 * Three knobs, matching what the UI exposes:
 *   - [isEnabled]      master switch. Off → 0 attempts, the error surfaces at once.
 *   - [maxAttempts]    1..5. Capped at 5 on purpose: the ladder is exponential,
 *                      and beyond five steps the turn would sit idle for minutes.
 *   - [intervalSec]    base delay between attempts, per attempt.
 *
 * The schedule handed to the retry loop is [intervalSec] repeated; a server
 * `Retry-After` still wins when it is larger (the provider layer already takes
 * the max). Keeping the base interval flat — instead of a second hidden ladder —
 * is what makes the setting predictable: "每 3 秒重试一次，最多 3 次".
 *
 * Storage is a SharedPreferences file of its own so a backup/restore of chat
 * data cannot silently change retry behaviour mid-run.
 */
object RetryPrefs {
    private const val PREFS = "minis_retry_prefs"
    private const val KEY_ENABLED = "autoRetryEnabled"
    private const val KEY_ATTEMPTS = "autoRetryMaxAttempts"
    private const val KEY_INTERVAL = "autoRetryIntervalSec"

    /** Hard ceiling the UI enforces; see the class doc for why 5. */
    const val MAX_ATTEMPTS = 5
    const val MIN_ATTEMPTS = 1
    const val DEFAULT_ATTEMPTS = 3

    const val MIN_INTERVAL_SEC = 1
    const val MAX_INTERVAL_SEC = 60
    const val DEFAULT_INTERVAL_SEC = 2

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = true

    @Volatile
    private var cachedAttempts: Int = DEFAULT_ATTEMPTS

    @Volatile
    private var cachedIntervalSec: Int = DEFAULT_INTERVAL_SEC

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Warm the cache from MinisApp.onCreate; [isEnabled] is then Context-free. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        val p = prefs(context)
        cachedEnabled = p.getBoolean(KEY_ENABLED, true)
        cachedAttempts = p.getInt(KEY_ATTEMPTS, DEFAULT_ATTEMPTS).coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)
        cachedIntervalSec = p.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SEC)
            .coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
    }

    fun isEnabled(): Boolean = cachedEnabled

    fun maxAttempts(): Int = cachedAttempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)

    fun intervalSec(): Int = cachedIntervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)

    /**
     * The backoff schedule handed to `HttpRetryAfter.delaySeconds`.
     *
     * Flat by design: [intervalSec] per attempt. An empty schedule would fall
     * back to the provider layer's own 2s default, so a disabled master switch is
     * expressed through [maxAttempts] rather than through this array.
     */
    fun schedule(): IntArray {
        val n = maxAttempts()
        return IntArray(n) { intervalSec() }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setMaxAttempts(context: Context, attempts: Int) {
        cachedAttempts = attempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)
        prefs(context).edit().putInt(KEY_ATTEMPTS, cachedAttempts).apply()
    }

    fun setIntervalSec(context: Context, seconds: Int) {
        cachedIntervalSec = seconds.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
        prefs(context).edit().putInt(KEY_INTERVAL, cachedIntervalSec).apply()
    }
}
