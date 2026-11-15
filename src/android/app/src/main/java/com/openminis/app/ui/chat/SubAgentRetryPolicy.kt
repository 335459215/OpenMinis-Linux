package com.openminis.app.ui.chat

import com.openminis.app.data.model.ModelEntry

/** [T-android-arch-split] Rate-limit bucket rotation, moved out of the sub-agent extension. */
/**
 * Re-pick a pool entry on a different rate-limit bucket (host + key + model).
 * Same model name on another key is a different bucket and is eligible.
 * Returns null when the pool has no other bucket (caller may stop on 429).
 */
internal fun pickRetryEntry(
        entries: List<ModelEntry>,
        pool: List<String>,
        excludeEntryId: String?,
        excludeGateKey: String?,
        gateKeyOf: (ModelEntry) -> String?,
        seed: Int,
    ): ModelEntry? {
        val pooled = pool.filter { it.isNotBlank() }.mapNotNull { id -> entries.find { it.id == id } }
        val rotated = pooled.filter { candidate ->
            if (excludeEntryId != null && candidate.id == excludeEntryId) return@filter false
            val gk = gateKeyOf(candidate)
            gk.isNullOrBlank() || excludeGateKey.isNullOrBlank() ||
                !com.openminis.app.provider.ProviderKeyGate.sameBucket(gk, excludeGateKey)
        }
        return if (rotated.isNotEmpty()) rotated[Math.floorMod(seed, rotated.size)] else null
    }
