package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.BuiltinFreeLanes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retired builtin lanes must not survive an upgrade as empty provider rows.
 *
 * Dropping a lane from the build does not remove the instance it seeded — the
 * row lives on in `provider_config.json` pointing at a loopback port nothing
 * binds, so every refresh fails and the model picker keeps offering models that
 * cannot answer. [BuiltinLaneRetirement] is the single decision point for which
 * shipped rows are dead, and [ProviderConfigStore.purgeRetiredBuiltInLanes] is
 * its only caller.
 *
 * Two retirement channels, one decision:
 *
 * - [BuiltinFreeLanes.RETIRED_IDS] remembers lanes whose whole build-shipment
 *   is gone (a2a) — the descriptor does not survive, so the id list is the
 *   memory.
 * - Built-in-prefixed rows absent from the active set retire: the active set
 *   is what the last successfully seeded manifest listed (persisted snapshot
 *   when the latest download failed), so a manifest that drops a lane retires
 *   exactly that lane, and an unreadable manifest retires nothing.
 */
class BuiltinLaneRetirementTest {

    private fun instance(id: String) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )

    @Test
    fun thePurgeListIsNotAlsoShipped() {
        // Ordering hazard guard: listing an id as retired while still seeding it
        // would delete a live row on every boot.
        val live = BuiltinFreeLanes.ALL.map { it.instanceId }.toSet()
        assertTrue(BuiltinFreeLanes.RETIRED_IDS.isNotEmpty())
        for (retired in BuiltinFreeLanes.RETIRED_IDS) {
            assertTrue("$retired is shipped and retired at once", retired !in live)
        }
    }

    @Test
    fun onlyRetiredIdsAreReturned() {
        val retired = BuiltinFreeLanes.RETIRED_IDS.first()
        val config = ProviderConfig(
            instances = mutableListOf(
                instance("user-own-relay"),
                instance(retired),
                instance(BuiltinFreeLanes.ZEN.instanceId),
            ),
        )

        assertEquals(listOf(retired), BuiltinLaneRetirement.instancesToPurge(config))
    }

    @Test
    fun userRowsAreNeverCandidates() {
        val config = ProviderConfig(
            instances = mutableListOf(instance("user-own-relay")),
        )

        assertTrue(BuiltinLaneRetirement.instancesToPurge(config).isEmpty())
    }

    // ---- active-set rule: manifest lanes retire when the manifest drops them ----

    @Test
    fun manifestLaneInTheActiveSetIsKept() {
        val config = ProviderConfig(instances = mutableListOf(instance("builtin-demo-free")))
        val active = BuiltinFreeLanes.ALL_INSTANCE_IDS + "builtin-demo-free"

        assertTrue(BuiltinLaneRetirement.instancesToPurge(config, active).isEmpty())
    }

    @Test
    fun manifestLaneAbsentFromTheActiveSetIsPurged() {
        val config = ProviderConfig(instances = mutableListOf(instance("builtin-demo-free")))
        val active = BuiltinFreeLanes.ALL_INSTANCE_IDS

        assertEquals(listOf("builtin-demo-free"), BuiltinLaneRetirement.instancesToPurge(config, active))
    }

    @Test
    fun aPurgeLeavesLiveLanesAndUserRowsAlone() {
        val retired = BuiltinFreeLanes.RETIRED_IDS.first()
        val config = ProviderConfig(
            instances = mutableListOf(
                instance("user-own-relay"),
                instance(retired),
                instance(BuiltinFreeLanes.ZEN.instanceId),
                instance("builtin-demo-free"),
                instance("builtin-another-free"),
            ),
        )
        val active = BuiltinFreeLanes.ALL_INSTANCE_IDS + "builtin-another-free"

        assertEquals(
            listOf(retired, "builtin-demo-free"),
            BuiltinLaneRetirement.instancesToPurge(config, active),
        )
    }

    @Test
    fun anUnreadableManifestKeepsManifestLanesAlive() {
        // The persisted snapshot models "the remote copy failed to parse": the
        // manifest lane's id is still in the veto set, so it must not retire.
        val config = ProviderConfig(instances = mutableListOf(instance("builtin-demo-free")))
        val persistedActiveIds = BuiltinFreeLanes.ALL_INSTANCE_IDS + "builtin-demo-free"

        assertTrue(BuiltinLaneRetirement.instancesToPurge(config, persistedActiveIds).isEmpty())
    }

    // ---- resident rule: rows we ship but do not seed as lanes ----

    @Test
    fun aResidentRowIsNeverPurgedByTheDefaultSet() {
        // The Agent2API bridge registers `builtin-agent2api-local` itself, so
        // the lane list cannot name it. Without the resident whitelist the
        // `builtin-` prefix rule retires it on every cold start and
        // removeInstance() takes its models, group membership and agent-loop
        // slots with it — the gateway's later sync restores the row and the
        // models, never the groups.
        val config = ProviderConfig(
            instances = mutableListOf(instance("builtin-agent2api-local")),
        )

        assertTrue(BuiltinLaneRetirement.instancesToPurge(config).isEmpty())
    }

    @Test
    fun aResidentSurvivesEvenWhenTheManifestRuled() {
        // The seed pass builds its active set from the manifest alone; the
        // resident must be in it, not rescued afterwards by the default.
        val config = ProviderConfig(
            instances = mutableListOf(instance("builtin-agent2api-local")),
        )
        val activeFromManifest = BuiltinFreeLanes.ALL_INSTANCE_IDS

        assertTrue(
            BuiltinLaneRetirement.instancesToPurge(config, activeFromManifest).isEmpty(),
        )
    }

    @Test
    fun theResidentIdMatchesTheBridgeConstant() {
        // The id lives in this package as a literal to avoid an app/a2a
        // dependency; this is the only thing stopping the two from drifting.
        // Renaming either side orphans every existing install's row.
        assertTrue(
            "A2aProviderBridge.INSTANCE_ID moved — update BuiltinFreeLanes.RESIDENT_INSTANCE_IDS",
            com.openminis.app.a2a.A2aProviderBridge.INSTANCE_ID in
                BuiltinFreeLanes.RESIDENT_INSTANCE_IDS,
        )
    }

    @Test
    fun aResidentIsNotALaneForManifestDedupe() {
        // ACTIVE_INSTANCE_IDS feeds retirement; ALL_INSTANCE_IDS feeds dedupe.
        // A manifest entry claiming the resident id must still be able to
        // synthesise, otherwise the split was wired to the wrong set.
        assertTrue(
            "builtin-agent2api-local leaked into the lane dedupe set",
            "builtin-agent2api-local" !in BuiltinFreeLanes.ALL_INSTANCE_IDS,
        )
    }
}
