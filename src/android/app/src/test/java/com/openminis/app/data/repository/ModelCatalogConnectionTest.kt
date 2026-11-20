package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelCatalogConnectionTest {
    private val instance = ProviderInstance(
        id = "relay",
        label = "My relay",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = "https://relay.invalid/api",
        appendV1Suffix = true,
        createdAt = 1L,
    )

    private fun fingerprint(provider: ProviderInstance) = ModelCatalogConnection.from(provider, 2, 3)

    @Test
    fun everyCatalogRequestFieldInvalidatesTheCapturedConnection() {
        val before = fingerprint(instance)
        val edits = listOf(
            instance.copy(customBaseURL = "https://other.invalid/api"),
            instance.copy(appendV1Suffix = false),
            instance.copy(providerType = ProviderType.anthropic),
            instance.copy(credentialType = ProviderCredential.oauth),
            instance.copy(customUserAgent = "ExampleClient/1"),
            instance.copy(useResponsesAPI = true),
            instance.copy(azureMode = true),
            instance.copy(createdAt = 2L),
        )
        edits.forEach { changed -> assertNotEquals(before, fingerprint(changed)) }
        assertNotEquals(before, ModelCatalogConnection.from(instance, 4, 3))
        assertNotEquals(before, ModelCatalogConnection.from(instance, 2, 4))
    }

    @Test
    fun equivalentEffectiveAddressKeepsTheRequestCurrent() {
        assertEquals(
            fingerprint(instance),
            fingerprint(instance.copy(customBaseURL = "https://relay.invalid/api/v1", appendV1Suffix = false)),
        )
    }

    @Test
    fun renamePinOrEnabledStateDoesNotChangeTheCatalogRequest() {
        val before = fingerprint(instance)
        assertEquals(before, fingerprint(instance.copy(label = "Renamed")))
        assertEquals(before, fingerprint(instance.copy(pinned = true)))
        assertEquals(before, fingerprint(instance.copy(isEnabled = false)))
    }

    @Test
    fun capturedFingerprintDoesNotFollowMutableInstanceEdits() {
        val before = fingerprint(instance)
        instance.customBaseURL = "https://edited.invalid"
        assertEquals("https://relay.invalid/api/v1", before.baseUrl)
        assertNotEquals(before, fingerprint(instance))
    }
}
