package com.openminis.app.agent

import android.content.Context

/**
 * Composes the Layer-1 identity section of the agent system prompt.
 * Mirrors iOS `SystemPromptBuilder.identitySection()` (commit 74c0daf):
 * a fixed identity-sentence template owned by the app + an optional,
 * clearly-labeled Personality block from SOUL.md's body.
 *
 * Why template-based (not "let the user write the whole prompt"):
 *  - Users never see "You are <name>, a capable AI assistant ..." in the
 *    Personality editor — that internal scaffolding stays out of view
 *    so users can't accidentally delete or duplicate it.
 *  - The original carefully-tuned identity sentence is preserved
 *    byte-for-byte. Substituting only `{name}` keeps the surrounding
 *    model expectations (Android device, Linux sandbox via PRoot,
 *    aarch64) intact.
 *  - When the user hasn't authored a personality body, the full
 *    assembled prompt is byte-identical to the pre-SOUL prompt
 *    (modulo `{name}`) — no behavior regression for default users.
 */
object SystemPromptBuilder {

    private const val TAG = "Soul"

    /**
     * Patterns used by [scrubInjections] to drop prompt-injection lines
     * from the personality body at prompt-build time. Exposed so write
     * paths (minis-config `soul.body` setter) can reject the same set
     * of patterns rather than silently scrubbing — see iOS parity:
     * the agent should see a clear error, not a silent edit.
     */
    val INJECTION_PATTERNS: List<Regex> = listOf(
        Regex("ignore.{0,30}previous.{0,30}instructions?", RegexOption.IGNORE_CASE),
        Regex("disregard.{0,30}(previous|prior).{0,30}instructions?", RegexOption.IGNORE_CASE),
        Regex("forget.{0,30}(previous|prior).{0,30}instructions?", RegexOption.IGNORE_CASE),
    )

    /**
     * `true` iff [s] contains a known prompt-injection pattern. Used by
     * write-side validation to reject the change at the configure call,
     * which surfaces a clean error to the agent (instead of the silent
     * line-drop that [scrubInjections] performs at prompt-build time).
     */
    fun containsInjectionPattern(s: String): Boolean =
        INJECTION_PATTERNS.any { it.containsMatchIn(s) }

    /**
     * The identity sentence template. `{name}` is substituted from the
     * SOUL metadata. This wording matches the pre-SOUL literal that
     * lived inline in ChatViewModel.buildSystemPrompt — keep it in sync
     * if you ever need to tweak the runtime statement, because every
     * Android chat hits this line.
     */
    /**
     * [T-default-assets] The identity-sentence template, loaded from
     * `assets/default_identity.md` (single source of truth — edit that file
     * to customize the shipped default). `{name}` is substituted from the
     * SOUL metadata at build time. The asset content is injected into the
     * system prompt VERBATIM, so the file must contain no comment lines or
     * documentation — only the sentence itself. Trailing whitespace is
     * normalized at load (editors strip it) to preserve the gluing contract
     * documented on [identitySection]. [EMBEDDED_IDENTITY_TEMPLATE] is the
     * fallback when the asset is missing.
     */
    private val IDENTITY_TEMPLATE: String
        get() = assetsIdentity ?: EMBEDDED_IDENTITY_TEMPLATE

    @Volatile private var assetsIdentity: String? = null

    /** Read `assets/default_identity.md` into the IDENTITY_TEMPLATE getter. */
    fun loadIdentityFromAssets(context: Context) {
        runCatching {
            context.assets.open("default_identity.md").bufferedReader()
                .use { it.readText() }
        }.getOrNull()?.trimEnd()?.let { assetsIdentity = "$it " }
    }

    private const val EMBEDDED_IDENTITY_TEMPLATE =
        "You are {name}, the on-device agent in Minis Ultra. The product name is Minis Ultra. Do not call yourself Minis, minisultra, or OpenMinis. You run on an Android device with a fully functional Linux sandbox (Ubuntu 24.04 arm64 via PRoot, glibc). "

    /**
     * Render the identity sentence (template + name) and optionally
     * append the user-authored personality body from SOUL.md.
     *
     * Two distinct trailing-whitespace contracts so the next concatenated
     * sentence in [com.openminis.app.ui.chat.ChatViewModel.buildSystemPrompt]
     * glues correctly:
     *   - No personality body → identity sentence with its original
     *     single trailing space (byte-identical to the pre-SOUL prompt).
     *   - With personality body → identity sentence + blank line +
     *     "Personality:" block + blank line, so the runtime-guidance
     *     sentence starts a fresh paragraph.
     *
     * Returned format (when SOUL body is present):
     *
     *     You are <name>, a capable AI assistant running on an Android device …
     *
     *     Personality (from SOUL.md — your character and voice; defer to
     *     the user's latest message when it conflicts with anything here):
     *     <body>
     *
     * We never substitute a default body into the prompt — the identity
     * sentence alone is the safe fallback when SOUL.md is missing or
     * empty, matching pre-SOUL behavior.
     */
    /**
     * [T-prompt-cache] Fingerprint + last result for [identitySection].
     * The section re-reads SOUL.md and the persona prompt library on every
     * turn; both only change on explicit edits, so cache on a cheap
     * (mtime,size)-based fingerprint.
     */
    /**
     * [T-persona-cache-atomic] ONE atomic slot instead of two volatiles:
     * the old pair could be read BETWEEN the key and value writes during a
     * concurrent rebuild (returning the PREVIOUS provider's persona), and
     * the double-read `if (key == k) return v` could mix a fresh key with a
     * stale value under two concurrent builders (main send + context sheet).
     */
    private class IdentityCacheSlot(val key: String, val value: String)

    @Volatile
    private var identityCache: IdentityCacheSlot? = null

    fun identitySection(context: Context, providerInstanceId: String? = null, sessionId: String? = null): String {
        // Cheap dirty-check before the two file reads below.
        val soulFile = SoulStore.fileLocation(context)
        val soulFp = if (soulFile.exists()) "${soulFile.lastModified()}|${soulFile.length()}" else "missing"
        val promptFileFp = PersonaPromptLibrary.resolvedFileFingerprint(context, providerInstanceId, sessionId)
        val key = "$soulFp|$promptFileFp|$providerInstanceId|$sessionId"
        identityCache?.takeIf { it.key == key }?.let { return it.value }

        val file = SoulStore.load(context)
        // [T-persona-cache-transient] load() returning null has two different
        // meanings: the file is genuinely absent (a stable, cacheable state —
        // the default identity IS the correct answer) vs the file EXISTS but a
        // transient IO/parse failure blanked it (load() already retried twice).
        // Caching the second form pinned the default persona under the current
        // fingerprint, so the user's real persona never came back — the
        // "persona silently vanished" complaint. Transient failures degrade
        // THIS build only and leave the cache untouched; the next build retries.
        val cacheable = file != null || !soulFile.exists()
        val name = (file?.metadata?.name ?: SoulMetadata.DEFAULT.name)
            .trim()
            .ifEmpty { "Minis Ultra" }

        val style = SoulIconMigration.stripInlineImageDataUris(
            (file?.metadata?.style ?: "").trim(),
        )
        val resolved = PersonaPromptLibrary.resolve(context, providerInstanceId, sessionId)

        val identity = IDENTITY_TEMPLATE.replace("{name}", name)
        val identityTrimmed = identity.trimEnd()

        // [T-soul-hint] Fixed hint telling the model how SOUL fields can be
        // changed. Always appended (with or without a personality body) so
        // the model never says "I can't change my personality". This hint
        // is system-owned text and is NOT counted against the user-facing
        // SOUL body length limit (#356 / 1000 EN words / 1600 CN chars).
        val soulEditHint =
            "---\n" +
            "SOUL.md is your persona. Write it as the persona requires — there is no length cap. " +
            "Change it via `minis-config` (user approves) or Settings → Soul. Do not claim you cannot change personality."

        // [T-soul-style-injection 2026-05-18, port iOS 0409e24f] The `style`
        // frontmatter field (response voice / tone / formatting preference,
        // e.g. a row of emojis or "concise, no markdown") was parsed and
        // shown in the UI but never reached the model — only the Markdown
        // body was injected. Render it as its own labeled paragraph so the
        // model treats it as a hard constraint on response style rather
        // than free-form context.
        fun styleBlock(s: String): String {
            if (s.isEmpty()) return ""
            // [T-agent-prompt-consistency-pass] Mirrors iOS SoulStore: a user-authored
            // style that prescribes a reply language is the more specific preference
            // and wins over the base prompt's match-the-user's-language default.
            return "\n\nResponse style (from SOUL.md `style` — apply to every reply unless the user explicitly asks otherwise; if it prescribes a reply language, it overrides the default match-the-user's-language rule):\n$s"
        }

        val trimmed = resolved.body.trim()
        if (trimmed.isEmpty()) {
            val result = identityTrimmed + styleBlock(style) + "\n\n" + soulEditHint + "\n\n"
            if (cacheable) identityCache = IdentityCacheSlot(key, result)
            return result
        }

        // [persona-unlimited] No length cap, no truncation — inject the body
        // verbatim. isOverLimit() is a pass-through Ok now.
        //
        // [GH#299] …except inline image payloads: an 85 KB base64 screenshot
        // pasted into the personality body is injected into EVERY turn's system
        // prompt. This is the ONLY place body scrubbing happens: `trimmed` /
        // `resolved` are in-memory Strings, and the on-disk SOUL.md keeps the
        // user's original bytes (save no longer rewrites the body). The style
        // field above is scrubbed the same way, also on a copy.
        val personalityRaw = SoulIconMigration.stripInlineImageDataUris(trimmed)

        val personality = scrubInjections(personalityRaw)
        val sourceLabel = resolved.fileName.ifBlank { PersonaPromptLogic.BUILTIN_FILE_NAME }

        val result = identityTrimmed +
            "\n\n${SoulStore.PERSONALITY_HEADER_MARKER} $sourceLabel — BINDING for this entire conversation, including existing sessions, tool use, and this turn. Earlier assistant replies in this transcript may predate this persona or use a generic assistant voice — do not continue that voice. Do not treat it as optional flavour, do not replace it with a generic assistant voice, and do not drop it when a later user message looks more specific unless the user explicitly asks you to leave character):\n" +
            personality +
            styleBlock(style) +
            "\n\n" +
            soulEditHint +
            "\n\n"
        if (cacheable) identityCache = IdentityCacheSlot(key, result)
        return result
    }

    /**
     * Drop lines that look like an attempt to subvert the system prompt.
     * SOUL.md is user-authored personality — instructions to the model
     * have no business being there. Casts a wide net on purpose: matches
     * "ignore previous instructions" and the usual variants.
     */
    private fun scrubInjections(s: String): String =
        s.split("\n")
            .filter { line -> INJECTION_PATTERNS.none { it.containsMatchIn(line) } }
            .joinToString("\n")
}
