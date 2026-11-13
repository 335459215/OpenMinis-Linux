package com.openminis.app.autogroup

/**
 * Immutable description of a single model entry that participates in auto grouping.
 *
 * This type intentionally carries only the fields the planner needs. It has no
 * dependency on Android, Compose, Room or any other project class so that the
 * planner can be unit tested as a pure JVM component.
 *
 * @param entryId stable identifier of the entry; empty ids are ignored by [AutoGroupPlanner.plan].
 * @param modelId raw model identifier (e.g. `"gpt-4o-audio"`), used for modality detection.
 * @param displayName human readable name, used as a secondary modality signal.
 * @param providerInstanceId identifier of the provider instance. **Not used for bucketing** —
 *        kept so callers can pass the full entry shape without a lossy mapping.
 * @param providerLabel human readable provider name. Also unused for bucketing.
 * @param isHidden when true the entry is excluded from planning.
 */
data class AutoGroupInput(
    val entryId: String,
    val modelId: String,
    val displayName: String,
    val providerInstanceId: String,
    val providerLabel: String,
    val isHidden: Boolean,
)

/**
 * Modality bucket a model belongs to.
 *
 * The declaration order is significant: [AutoGroupPlanner.plan] uses it as the output
 * ordering (`VISION < IMAGE < AUDIO < TEXT`).
 */
enum class AutoGroupModality { VISION, IMAGE, AUDIO, TEXT }

/**
 * One planned group.
 *
 * @param name final group name, already prefixed with [AutoGroupPlanner.AUTO_PREFIX].
 * @param entryIds member entry ids, kept in stable input order.
 */
data class AutoGroupPlan(val name: String, val entryIds: List<String>)

/**
 * Pure, dependency free planner that derives an automatic grouping of model entries.
 *
 * The planner never performs IO, never touches Android APIs and is stateless, so every
 * function can be called from any thread and is trivially testable.
 *
 * ### Grouping rule (capability only, one level)
 *
 * Entries are bucketed by **capability / modality** alone. The provider instance is
 * deliberately NOT part of the key: every vision model from every channel lands in the same
 * `[自动] 视觉` group, likewise for image / audio / text.
 *
 * A channel is only *how* a model is reached, not *what* it can do. Splitting by channel
 * fragments one capability across many groups — with three vision-capable providers you get
 * three near-identical vision groups — which defeats the purpose of grouping: pick a
 * capability, not a vendor.
 *
 * Empty buckets are never emitted. Every produced name is prefixed with [AUTO_PREFIX],
 * e.g. `[自动] 视觉`.
 */
object AutoGroupPlanner {

    /** Prefix marking an automatically managed group. Value is `"[自动] "` (with trailing space). */
    const val AUTO_PREFIX: String = "[自动] "

    /** Chinese display label of each modality. */
    private fun modalityLabel(modality: AutoGroupModality): String = when (modality) {
        AutoGroupModality.VISION -> "视觉"
        AutoGroupModality.IMAGE -> "图像"
        AutoGroupModality.AUDIO -> "语音"
        AutoGroupModality.TEXT -> "文本"
    }

    /**
     * AUDIO specific tokens. They are checked **before** the broad VISION tokens so that
     * `gpt-4o-audio` resolves to [AutoGroupModality.AUDIO] instead of matching the broad
     * `gpt-4o` VISION token. `audio` and `realtime` are treated as strictly audio-serving
     * substrings; `realtime` covers OpenAI realtime (speech-to-speech) models that do not
     * carry the literal `audio` token.
     */
    private val AUDIO_SPECIFIC_TOKENS = listOf("audio", "realtime")

    /** Tokens that unambiguously describe audio models. */
    private val AUDIO_TOKENS = listOf(
        "whisper", "tts", "voice", "audio", "speech", "realtime", "gpt-4o-audio",
    )

    /** Tokens that describe vision / multimodal-input models. */
    private val VISION_TOKENS = listOf(
        "vision", "vl", "gpt-4o", "gpt-4.1", "gpt-5", "claude-3", "claude-4",
        "claude-sonnet", "claude-opus", "gemini", "qwen-vl", "glm-4v", "llava",
        "pixtral", "internvl",
    )

    /** Tokens that describe image generation models. */
    private val IMAGE_TOKENS = listOf(
        "image", "dall-e", "dalle", "gpt-image", "imagen", "flux", "stable-diffusion",
        "sd3", "midjourney", "kolors", "wanx",
    )

    /**
     * Determine the [AutoGroupModality] of a model.
     *
     * Matching is a case-insensitive substring test performed on both the lowercased
     * [modelId] and the lowercased [displayName]. Evaluation order is deliberate:
     *
     * 1. **AUDIO-specific guard** ([AUDIO_SPECIFIC_TOKENS] = `audio`, `realtime`) runs first,
     *    on both fields. Any match immediately yields [AutoGroupModality.AUDIO].
     * 2. [AutoGroupModality.VISION] broad tokens (`gpt-4o`, `claude-3`, `gemini`, ...).
     * 3. [AutoGroupModality.IMAGE] tokens.
     * 4. [AutoGroupModality.AUDIO] remaining tokens (`whisper`, `tts`, `speech`, ...).
     * 5. Otherwise [AutoGroupModality.TEXT].
     *
     * Because of step 1, `gpt-4o-audio` → AUDIO while a plain `gpt-4o` still → VISION.
     * The same reasoning keeps `gpt-4o-realtime` → AUDIO.
     *
     * @param modelId raw model id; may be empty.
     * @param displayName human readable model name; may be empty.
     * @return the resolved modality, never null.
     */
    fun modalityOf(modelId: String, displayName: String): AutoGroupModality {
        val haystacks = listOf(modelId.lowercase(), displayName.lowercase())

        // 1. Audio-specific guard, before the broad VISION tokens.
        if (matchesAny(haystacks, AUDIO_SPECIFIC_TOKENS)) return AutoGroupModality.AUDIO

        // 2. Vision (broadest category).
        if (matchesAny(haystacks, VISION_TOKENS)) return AutoGroupModality.VISION

        // 3. Image generation.
        if (matchesAny(haystacks, IMAGE_TOKENS)) return AutoGroupModality.IMAGE

        // 4. Remaining audio tokens.
        if (matchesAny(haystacks, AUDIO_TOKENS)) return AutoGroupModality.AUDIO

        // 5. Fallback.
        return AutoGroupModality.TEXT
    }

    /** True when any of [tokens] is a substring of any haystack in [haystacks]. */
    private fun matchesAny(haystacks: List<String>, tokens: List<String>): Boolean =
        haystacks.any { hay -> tokens.any { token -> hay.contains(token) } }

    /**
     * Build the group plan.
     *
     * Behavior:
     * - Entries with `isHidden == true` are skipped.
     * - Entries with a blank `entryId` are skipped.
     * - A given `entryId` lands in at most one group (first occurrence wins; later
     *   duplicates are ignored).
     * - Bucketing uses **modality only**. `providerInstanceId` / `providerLabel` are ignored
     *   on purpose, so the same capability from several channels ends up in ONE group.
     * - Member `entryIds` preserve the input order.
     *
     * [rebuild] does **not** change the produced names: merge (`false`) and rebuild (`true`)
     * produce identical output. `rebuild == true` only signals to the caller that
     * pre-existing auto groups (identified by [AUTO_PREFIX]) may be removed; `plan` itself
     * never deletes anything, and [existingNames] is accepted for caller-side merge
     * de-duplication only.
     *
     * Output ordering is deterministic: `VISION < IMAGE < AUDIO < TEXT`. Empty buckets are
     * omitted.
     *
     * @param items input entries.
     * @param rebuild true for rebuild mode, false for merge mode.
     * @param existingNames currently existing group names (merge mode); accepted but not
     *        mutated, the caller is responsible for appending de-duplication.
     * @return planned groups, empty when nothing is left to group.
     */
    fun plan(
        items: List<AutoGroupInput>,
        rebuild: Boolean,
        existingNames: List<String>,
    ): List<AutoGroupPlan> {
        @Suppress("UNUSED_EXPRESSION") existingNames // merge de-dup is the caller's concern.
        @Suppress("UNUSED_EXPRESSION") rebuild     // deletion is the caller's concern.

        if (items.isEmpty()) return emptyList()

        val seenEntryIds = HashSet<String>()
        // modality -> entryIds. Channel is deliberately NOT part of the key.
        val byModality = LinkedHashMap<AutoGroupModality, MutableList<String>>()

        for (item in items) {
            if (item.isHidden) continue
            if (item.entryId.isEmpty()) continue
            if (!seenEntryIds.add(item.entryId)) continue

            val modality = modalityOf(item.modelId, item.displayName)
            byModality.getOrPut(modality) { ArrayList() }.add(item.entryId)
        }

        // Deterministic order: VISION < IMAGE < AUDIO < TEXT.
        return byModality.keys
            .sortedBy { it.ordinal }
            .map { modality ->
                AutoGroupPlan(
                    AUTO_PREFIX + modalityLabel(modality),
                    byModality.getValue(modality).toList(),
                )
            }
    }
}
