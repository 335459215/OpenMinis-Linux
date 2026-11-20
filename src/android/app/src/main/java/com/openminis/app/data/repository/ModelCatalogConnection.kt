package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType

/** Metadata only: credentials and headers are represented by revisions, never secret values. */
internal data class ModelCatalogConnection(
    val providerType: ProviderType,
    val credentialType: ProviderCredential,
    val baseUrl: String?,
    val userAgent: String?,
    val responsesApi: Boolean,
    val azureMode: Boolean,
    val createdAt: Long,
    val credentialRevision: Long,
    val headerRevision: Long,
) {
    companion object {
        fun from(instance: ProviderInstance, credentialRevision: Long, headerRevision: Long) =
            ModelCatalogConnection(
                instance.providerType, instance.credentialType, instance.effectiveBaseURL,
                instance.customUserAgent, instance.useResponsesAPI, instance.azureMode,
                instance.createdAt, credentialRevision, headerRevision,
            )
    }
}
