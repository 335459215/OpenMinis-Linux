package com.openminis.app.ui.settings

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderType

// [G-P3] Extracted verbatim from AddProviderScreen so the file stays under
// its architecture-policy line ceiling. Pure mapping — no Compose dependency.

/** Returns available credential types per provider. */
internal fun availableCredentials(type: ProviderType): List<ProviderCredential> {
    return when (type) {
        ProviderType.openRouter -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        ProviderType.anthropic,
        ProviderType.openAI -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        ProviderType.gemini -> listOf(ProviderCredential.apiKey)
        // xAI Grok primarily targets SuperGrok / X Premium+ OAuth, but also
        // offers a plain API key path (api.x.ai). Mirror OpenAI's pattern
        // of exposing both so users on tiers without OAuth API access can
        // still configure manually.
        ProviderType.xAI -> listOf(ProviderCredential.oauth, ProviderCredential.apiKey)
        // [T-kimi-oauth] Primary target is the Coding Plan device-code
        // sign-in; a manual Moonshot API key remains available.
        ProviderType.kimiCode -> listOf(ProviderCredential.oauth, ProviderCredential.apiKey)
        // [T-android-provider-type-parity] Responses API instances authenticate
        // exactly like OpenAI ones (API key, or a Codex OAuth login).
        ProviderType.openAIResponses -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        // [G-P3] Gateway: no key of its own (channel accounts hold keys);
        // apiKey accepted so the standard credential flow applies.
        ProviderType.gateway -> listOf(ProviderCredential.apiKey)
        // Undrivable types: an API key is the only thing worth showing, and the
        // screen never offers them for creation anyway.
        ProviderType.antigravity,
        ProviderType.unsupported -> listOf(ProviderCredential.apiKey)
    }
}
