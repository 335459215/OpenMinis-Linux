package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderCredentialEditsRevisionTest {
    @Test
    fun editOnlyInvalidatesTheAffectedProvider() {
        val provider = "catalog-revision-a"
        val unrelated = "catalog-revision-b"
        val before = ProviderCredentialEdits.revision(provider)
        val unrelatedBefore = ProviderCredentialEdits.revision(unrelated)
        ProviderCredentialEdits.changed(provider)
        assertEquals(before + 1, ProviderCredentialEdits.revision(provider))
        assertEquals(unrelatedBefore, ProviderCredentialEdits.revision(unrelated))
    }
}
