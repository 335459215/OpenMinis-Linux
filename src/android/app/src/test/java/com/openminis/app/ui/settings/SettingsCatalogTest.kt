package com.openminis.app.ui.settings

import com.openminis.app.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect entry-point reachability when regrouping the settings home. */
class SettingsCatalogTest {
    @Test
    fun everyExistingFeatureHasExactlyOneCategory() {
        val destinations = SettingsCategory.entries.flatMap { it.groups.flatten() }
        assertEquals(33, destinations.size)
        assertEquals(SettingsDestination.entries.toSet(), destinations.toSet())
        assertEquals(destinations.size, destinations.toSet().size)
    }

    @Test
    fun runtimeTuningRemainsReachableThroughAgentSettings() {
        assertTrue(SettingsDestination.RUNTIME in SettingsCategory.AGENT.groups.flatten())
        assertEquals(Routes.AGENT_RUNTIME, SettingsDestination.RUNTIME.route)
        assertTrue(SettingsCategory.AGENT.groups.flatten().containsAll(listOf(
            SettingsDestination.MULTI_AGENT,
            SettingsDestination.LIFECYCLE_HOOKS,
            SettingsDestination.SOUL,
        )))
    }

    @Test
    fun credentialsAndGrantsAreGroupedAwayFromToolLaunchers() {
        val connections = SettingsCategory.CONNECTIONS.groups.flatten()
        assertTrue(connections.containsAll(listOf(SettingsDestination.ENV_VARS, SettingsDestination.SSH_HOSTS)))
        val tools = SettingsCategory.TOOLS.groups.flatten()
        assertFalse(SettingsDestination.PERMISSIONS in tools)
        assertFalse(SettingsDestination.ENV_VARS in tools)
    }

    @Test
    fun categoryIdsSurviveRouteRestorationAndUnknownIdsAreRejected() {
        assertEquals(SettingsCategory.entries.size, SettingsCategory.entries.map { it.id }.toSet().size)
        SettingsCategory.entries.forEach { category ->
            assertEquals(category, SettingsCategory.fromId(category.id))
            assertEquals("settings/category/${category.id}", Routes.settingsCategory(category.id))
        }
        assertNull(SettingsCategory.fromId(null))
        assertNull(SettingsCategory.fromId("removed-or-corrupt"))
    }

    @Test
    fun externalAndSheetActionsRemainExplicitAndDoNotNavigateToBlankRoutes() {
        assertEquals(setOf(
            SettingsDestination.A2A,
            SettingsDestination.SSH_WORKSPACE,
            SettingsDestination.PRIVACY_POLICY,
            SettingsDestination.FEEDBACK,
        ), SettingsDestination.entries.filter { it.route == null }.toSet())
        assertTrue(SettingsDestination.entries.mapNotNull { it.route }.all { it.isNotBlank() })
    }

    @Test
    fun searchFindsHiddenDescriptionsAndCombinesTermsAcrossFields() {
        assertTrue(matchesSettingsSearch("retry", "Agent", "Runtime", "Compaction and retry settings"))
        assertTrue(matchesSettingsSearch("  AGENT   retry  ", "Agent", "Retry options"))
        assertTrue(matchesSettingsSearch("压缩", "智能体", "自动压缩阈值"))
        assertFalse(matchesSettingsSearch("retry network", "Agent", "Retry options"))
        assertFalse(matchesSettingsSearch("unrelated", "Agent", "Memory"))
        assertTrue(matchesSettingsSearch("   ", "Any setting"))
    }
}
