package com.openminis.app.crash

import android.app.Application
import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash-burst detector and crash-loop guard.
 *
 * Scans `filesDir/logs/` at app launch for `crash-*.log` (ACRA) and
 * `native-crash-*.log` (NDK signal handler) files modified within the
 * last hour. When the count crosses [THRESHOLD] the app enters
 * safe-mode for that boot: MinisApp skips all heavy subsystem init so a
 * deterministic crash cannot rewrite its own evidence, and the next
 * launch is pinned to Home so the user is not dropped back into the
 * session that crashed.
 *
 * This build deliberately carries NO log-export surface. There is no
 * share dialog, no zip packaging, no `ACTION_SEND`, no email hand-off,
 * no "send diagnostics" prompt — crash files stay on the device and are
 * only readable by the user through Settings → Logs. Detection exists
 * purely to keep the app bootable.
 *
 * Self-contained on purpose: zero app-state dependencies (no
 * AppLogger / ChatViewModel / ProviderRepository etc), uses
 * `android.util.Log` directly, every entry point wrapped in try/catch.
 * Runs early enough that a partial-init / pre-DI launch can't break it.
 */
object CrashFrequencyDetector {
    private const val TAG = "CrashFreqDetector"
    private const val WINDOW_MS: Long = 60L * 60L * 1000L  // 1 hour
    private const val THRESHOLD = 2

    // Persisted "burst already handled at" timestamp. Only crash files
    // whose mtime is GREATER than this timestamp count toward the
    // threshold on later cold starts. Without it the same pre-existing
    // crash files keep tripping safe-mode on every launch until they age
    // out of the one-hour window — the app would never finish booting.
    private const val PREFS = "crash_freq_prefs"
    private const val KEY_DISMISSED_AT = "dismissed_at"

    // Force-home grace period: once a crash burst trips, every cold start
    // for the next FORCE_HOME_GRACE_MS lands on Home regardless of the
    // user's "Launch Session" preference. Same idea as HangDetector's
    // shouldForceHomeOnLaunch, but driven by crash files rather than hang
    // heartbeats.
    private const val KEY_FORCE_HOME_UNTIL = "force_home_until"
    private const val FORCE_HOME_GRACE_MS: Long = 60L * 60L * 1000L  // 1 hour

    /**
     * Listeners notified when safe-mode transitions ON → OFF. Lets
     * subscribers (ChatViewModel etc.) retry the init work they skipped
     * while safe-mode was active. CopyOnWriteArrayList keeps add/iterate
     * thread-safe with zero locking on the hot read path.
     */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Register a callback fired once when safe-mode flips OFF. Returns
     * an unsubscribe lambda. Listeners are invoked on the calling thread
     * of [setSafeMode], wrapped in runCatching so a misbehaving listener
     * can't stall the others.
     */
    @JvmStatic
    fun registerSafeModeClearedListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun fireSafeModeCleared() {
        listeners.toList().forEach { runCatching { it() } }
    }

    /** Centralized setter so the ON → OFF edge can fire listeners exactly once. */
    private fun setSafeMode(value: Boolean) {
        val was = _safeMode.getAndSet(value)
        if (was && !value) {
            android.util.Log.i(TAG, "safe-mode cleared, firing ${listeners.size} listener(s)")
            fireSafeModeCleared()
        }
    }

    private fun getDismissedAt(ctx: Context): Long =
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DISMISSED_AT, 0L)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "getDismissedAt failed: ${t.message}")
            0L
        }

    private fun setDismissedAt(ctx: Context, ts: Long) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_DISMISSED_AT, ts).apply()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setDismissedAt failed: ${t.message}")
        }
    }

    private fun setForceHomeUntil(ctx: Context, ts: Long) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_FORCE_HOME_UNTIL, ts).apply()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setForceHomeUntil failed: ${t.message}")
        }
    }

    /**
     * AppNavigation.LaunchedEffect calls this on cold start. Returns true
     * while a recent crash burst is still inside the
     * [FORCE_HOME_GRACE_MS] grace window. Pairs with
     * [HangDetector.shouldForceHomeOnLaunch] so the launch resolver has a
     * single combined "land on Home this time, the previous run wasn't
     * healthy" signal.
     *
     * Self-expiring: returns false once the grace timestamp has passed,
     * so the user's "Launch Session" preference reasserts itself
     * automatically.
     */
    @JvmStatic
    fun shouldForceHomeOnLaunch(context: Context): Boolean {
        val until = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_FORCE_HOME_UNTIL, 0L)
        } catch (t: Throwable) {
            return false
        }
        return until > 0 && System.currentTimeMillis() < until
    }

    /**
     * When [checkAtLaunch] finds the crash burst, flip this flag so heavy
     * session-restore code paths (e.g. ChatViewModel.loadSession) can
     * short-circuit and avoid re-tripping the same crash before the user
     * has acknowledged the recovery screen.
     *
     * Kept as a static getter to preserve the "zero Minis internal
     * dependencies" contract — callers from anywhere in the app can
     * check the flag without introducing a reverse import on this file.
     */
    private val _safeMode = AtomicBoolean(false)

    @JvmStatic
    fun isSafeMode(): Boolean = _safeMode.get()

    /**
     * Clear safe-mode explicitly. Called by the recovery screen before it
     * restarts the process: the burst timestamp was already checkpointed
     * by [checkAtLaunch], so the next boot re-scans cleanly instead of
     * landing on the recovery screen forever.
     */
    @JvmStatic
    fun clearSafeMode() {
        setSafeMode(false)
    }

    /**
     * Inspect `filesDir/logs/` and decide whether this boot needs
     * crash-loop protection. Safe to call from Application.onCreate;
     * never throws.
     *
     * On a detected burst there is no prompt to show — the checkpoint is
     * written immediately, so the next launch scans clean and the app
     * boots normally onto Home. This is what keeps the guard from
     * becoming a permanent boot loop now that nothing asks the user to
     * dismiss anything.
     */
    fun checkAtLaunch(app: Application) {
        try {
            val logsDir = File(app.filesDir, "logs")
            if (!logsDir.isDirectory) return

            val all = logsDir.listFiles { f ->
                val n = f.name
                (n.startsWith("crash-") || n.startsWith("native-crash-")) && n.endsWith(".log")
            } ?: return

            val now = System.currentTimeMillis()
            // Dismiss marker is window-scoped, not permanent. While it is
            // still inside the one-hour window we keep filtering files
            // older than it (so a burst already handled does not re-trip
            // on the immediately following launch). Once the marker ages
            // out, any crash files still inside the live window are by
            // definition newer than what was handled last time.
            val rawDismissedAt = getDismissedAt(app)
            val dismissedAt = if (rawDismissedAt > 0 && now - rawDismissedAt <= WINDOW_MS) {
                rawDismissedAt
            } else {
                0L
            }

            val recent = all.filter {
                val mt = it.lastModified()
                mt > dismissedAt && now - mt <= WINDOW_MS
            }

            android.util.Log.i(
                TAG,
                "checkAtLaunch: total=${all.size} recent=${recent.size} " +
                    "threshold=$THRESHOLD dismissedAt=$dismissedAt",
            )

            if (recent.size < THRESHOLD) return

            // Enter safe-mode BEFORE the rest of MinisApp.onCreate
            // finishes and any Activity / VM starts restoring sessions.
            setSafeMode(true)

            // Pin the next launch to Home even after this boot is
            // resolved: the session that was open is the likeliest
            // trigger, and re-entering it is exactly the loop we are
            // breaking.
            setForceHomeUntil(app, now + FORCE_HOME_GRACE_MS)

            // Checkpoint the burst so the FOLLOWING launch scans clean.
            // Without this write the same files stay in the live window
            // and every restart re-trips safe-mode — an unbreakable boot
            // loop now that no dialog clears the flag.
            setDismissedAt(app, now)

            android.util.Log.w(
                TAG,
                "Recent crashes: ${recent.size} in last hour — safe-mode ON, " +
                    "force-home grace set, burst checkpointed",
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "checkAtLaunch failed: ${t.message}")
        }
    }
}
