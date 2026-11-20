package com.openminis.app.data.repository

import com.openminis.app.provider.ModelRefreshGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogPublicationTest {
    private data class Connection(val endpoint: String, val credentialRevision: Long = 0)
    private val lock = Any()
    private val generations = ModelRefreshGeneration()

    @Test
    fun staleRequestDoesNotReadCredentialsOrContactTheOldEndpoint() {
        val captured = Connection("https://first.invalid/v1")
        val current = captured.copy(endpoint = "https://second.invalid/v1")
        var requests = 0
        val result = withCurrentModelCatalogConnection(lock, captured, { current }) {
            requests++
            "credential-dependent request"
        }
        assertNull(result)
        assertEquals(0, requests)
    }

    @Test
    fun currentRequestMayResolveAnAbsentKeyWithoutBeingTreatedAsStale() {
        val captured = Connection("https://relay.invalid/v1")
        val result = withCurrentModelCatalogConnection(lock, captured, { captured }) {
            assertTrue(Thread.holdsLock(lock))
            (null as String?) to Unit
        }
        assertTrue(result != null)
        assertNull(result?.first)
    }

    @Test
    fun endpointEditDiscardsTheOldResponseWithoutStartingAnotherRefresh() {
        val captured = Connection("https://first.invalid/v1")
        val generation = generations.begin("p")
        var current = captured
        var writes = 0
        current = current.copy(endpoint = "https://second.invalid/v1")

        assertFalse(publishModelCatalogIfCurrent(lock, generations, "p", generation, captured, { current }) {
            writes++
        })
        assertEquals(0, writes)
    }

    @Test
    fun credentialEditDiscardsTheOldResponseWithoutReadingAnySecret() {
        val captured = Connection("https://relay.invalid/v1", credentialRevision = 1)
        val generation = generations.begin("p")
        var writes = 0

        assertFalse(publishModelCatalogIfCurrent(
            lock, generations, "p", generation, captured,
            { captured.copy(credentialRevision = 2) },
        ) { writes++ })
        assertEquals(0, writes)
    }

    @Test
    fun removalDoesNotRecreateCatalogEntriesForTheDeletedProvider() {
        val generation = generations.begin("p")
        var writes = 0
        assertFalse(publishModelCatalogIfCurrent(
            lock, generations, "p", generation, Connection("https://relay.invalid/v1"), { null },
        ) { writes++ })
        assertEquals(0, writes)
    }

    @Test
    fun unchangedConnectionPublishesUnderTheConfigurationLock() {
        val captured = Connection("https://relay.invalid/v1")
        val generation = generations.begin("p")
        var writes = 0
        assertTrue(publishModelCatalogIfCurrent(lock, generations, "p", generation, captured, { captured }) {
            assertTrue(Thread.holdsLock(lock))
            writes++
        })
        assertEquals(1, writes)
    }

    @Test
    fun unchangedConnectionStillRejectsASupersededRefresh() {
        val captured = Connection("https://relay.invalid/v1")
        val stale = generations.begin("p")
        generations.begin("p")
        var writes = 0
        assertFalse(publishModelCatalogIfCurrent(lock, generations, "p", stale, captured, { captured }) {
            writes++
        })
        assertEquals(0, writes)
    }
}
