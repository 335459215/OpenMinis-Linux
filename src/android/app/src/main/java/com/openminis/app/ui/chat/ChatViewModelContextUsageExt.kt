package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart

/**
 * [T-arch-extract-context-usage] Context-usage measurement, extracted out of
 * `ChatViewModel.kt` so the architecture ratchet (a per-file line ceiling that
 * never rises) keeps holding.
 *
 * `contextTokensForPolicy()` lives in [ChatViewModelTokenExt] (upstream's
 * version, which adds the untrimmed effective-history floor the compact gate
 * needs — see [T-android-inloop-compact-truthy-gate]); the strip helpers live
 * in [ChatViewModelChatMappingExt], which delegates to
 * [com.openminis.app.data.repository.PreviewText.stripSystemReminders] as the
 * single stripping authority. Only the retry policy remains here.
 */

/**
 * [T-arch-extract-retry] How many automatic retries a transient failure gets.
 *
 * Extracted out of `ChatViewModel.kt` (per-file line ceiling). The user-facing
 * knobs live in [com.openminis.app.data.RetryPrefs]:
 *   - master switch off  → 0 attempts, the error surfaces immediately;
 *   - otherwise the per-model override is clamped to the user's ceiling
 *     (1..5), because a model override written before the setting existed must
 *     not silently exceed what the UI now advertises.
 */
internal fun ChatViewModel.effectiveMaxRetries(): Int {
    if (!com.openminis.app.data.RetryPrefs.isEnabled()) return 0
    val userCap = com.openminis.app.data.RetryPrefs.maxAttempts()
    val configured = activeOverrides()?.maxRetries ?: userCap
    return minOf(configured, userCap).coerceIn(0, com.openminis.app.data.RetryPrefs.MAX_ATTEMPTS)
}
