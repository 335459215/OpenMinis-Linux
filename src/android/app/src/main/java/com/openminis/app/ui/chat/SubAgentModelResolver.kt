package com.openminis.app.ui.chat

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.ModelAliasMatcher

/**
 * [T-subagent-pin-main-provider] Decide which model a sub-agent runs on.
 *
 * Hard invariant: **a sub-agent runs on the same provider instance as the chat
 * that spawned it.** The user picks a channel for the conversation; dispatching
 * background work to a different channel silently changes billing, quota, rate
 * limits and model behaviour behind their back.
 *
 * What went wrong before (real failure, not hypothetical):
 *  - an empty slot pool fell back to `config.modelEntries`, i.e. *every*
 *    configured provider, so the candidate set already spanned channels;
 *  - `MultiAgentSettings.pickModelId` only checked "is this slot filled?", never
 *    "is this slot on the same provider?", and round-robined across whatever the
 *    slots held;
 *  - the active session model was the **last** fallback, not the anchor.
 *
 * Net effect: a chat running `gcmod/deepseek-v4.1-flash` could spawn a lane on
 * `newapi/DeepSeek-OCR` or `builtin-opencode-zen/*`, which then failed with
 * `model_not_found` because those entries were not in the Agent Loop visible set.
 *
 * Preference order, all constrained to the pinned provider:
 *  1. the explicit `model` argument from the spawn call;
 *  2. the configured sub-agent slot for this lane index;
 *  3. the main session model.
 */
internal object SubAgentModelResolver {

    data class Resolution(
        val entry: ModelEntry,
        /** True when the main session model won (no same-provider slot applied). */
        val pinnedToMain: Boolean,
        /**
         * Slots that survived [pinToProvider]. Retry rotation MUST reuse this
         * list, not the raw stored slots: a 429 retry that rotates onto another
         * provider reintroduces exactly the cross-channel dispatch this class
         * exists to prevent.
         */
        val pinnedSlots: List<String>,
    )

    fun resolve(
        config: ProviderConfig,
        mainEntryId: String?,
        storedSlots: List<String>,
        maxConcurrent: Int,
        requested: String?,
        roundRobinIndex: Int,
    ): Resolution? {
        val mainEntry = mainEntryId?.let { id -> config.modelEntries.find { it.id == id } }
        val mainProvider = mainEntry?.providerInstanceId

        val slots = pinToProvider(
            MultiAgentSettings.retainLive(
                storedSlots,
                config.modelEntries.map { it.id }.toSet(),
                maxConcurrent,
            ),
            config,
            mainProvider,
        )
        val slotEntries = slots.mapNotNull { id -> config.modelEntries.find { it.id == id } }
        // Candidates never span providers: the pinned slots plus the chat's own
        // model. Only when there is no anchor at all does the full list stay
        // eligible (no session model selected yet).
        val candidates = (slotEntries + listOfNotNull(mainEntry))
            .distinctBy { it.id }
            .ifEmpty { config.modelEntries }

        val requestedEntry = requested?.let { modelId ->
            candidates.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
                ?: ModelAliasMatcher.resolveBest(
                    modelId,
                    candidates,
                    idOf = { it.model.id },
                    nameOf = { it.model.displayName },
                )
        }
        val picked = requestedEntry
            ?: pickFromSlots(slots, roundRobinIndex, config)
            ?: mainEntry
            ?: return null
        return Resolution(
            entry = picked,
            pinnedToMain = requestedEntry == null && picked.id == mainEntry?.id,
            pinnedSlots = slots,
        )
    }

    /**
     * Round-robin over the *pinned* slots only. A lane whose slot was blanked
     * by [pinToProvider] contributes nothing, so it cannot drag in another
     * provider — that was the exact leak this replaces.
     */
    private fun pickFromSlots(
        slots: List<String>,
        roundRobinIndex: Int,
        config: ProviderConfig,
    ): ModelEntry? {
        if (slots.isEmpty() || slots.all { it.isBlank() }) return null
        val slot = slots[Math.floorMod(roundRobinIndex, slots.size)]
        return slot.takeIf { it.isNotBlank() }
            ?.let { id -> config.modelEntries.find { it.id == id } }
    }

    /** Blank out slots that point at a provider other than [mainProvider]. */
    fun pinToProvider(
        slots: List<String>,
        config: ProviderConfig,
        mainProvider: String?,
    ): List<String> {
        if (mainProvider.isNullOrBlank()) return slots
        return slots.map { id ->
            when {
                id.isBlank() -> ""
                config.modelEntries.find { it.id == id }?.providerInstanceId == mainProvider -> id
                else -> ""
            }
        }
    }

    /** One-line channel report for logcat: `provider/label`. */
    fun describe(config: ProviderConfig, entry: ModelEntry): String {
        val provider = config.instances
            .find { it.id == entry.providerInstanceId }
            ?.label
            ?: entry.providerInstanceId.take(8)
        return "$provider/${entry.model.displayName.ifBlank { entry.model.id }}"
    }
}
