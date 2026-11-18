package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-001] A sub-agent must run on the main session's channel — same provider,
 * and by default the same model. Before [SubAgentModelResolver] existed the
 * resolution fell back to *every* configured model entry and the round-robin
 * rotated across providers, so a chat on one channel silently dispatched
 * sub-agents to another (observed: gcmod/deepseek main → newapi/DeepSeek-OCR
 * lanes). These tests pin the invariant the class was written for.
 */
class SubAgentModelResolverTest {

    private fun instance(id: String, label: String, type: ProviderType = ProviderType.openAI) =
        ProviderInstance(
            id = id,
            label = label,
            providerType = type,
            credentialType = ProviderCredential.apiKey,
        )

    private fun entry(uuid: String, provider: String, modelId: String, display: String = modelId) =
        ModelEntry(
            providerInstanceId = provider,
            baseModel = LLMModel(id = modelId, displayName = display, provider = provider),
            uuid = uuid,
        )

    /** Two channels, two models each — the shape of the real bug report. */
    private fun config() = ProviderConfig(
        instances = mutableListOf(
            instance("gcmod", "GCMOD"),
            instance("newapi", "NewAPI"),
        ),
        modelEntries = mutableListOf(
            entry("m-deepseek", "gcmod", "deepseek-v4.1-flash"),
            entry("m-glm", "gcmod", "glm-5.2"),
            entry("m-ocr", "newapi", "DeepSeek-OCR"),
            entry("m-kimi", "newapi", "kimi-k2"),
        ),
    )

    private fun resolve(
        slots: List<String> = emptyList(),
        mainEntryId: String? = "m-deepseek",
        requested: String? = null,
        roundRobinIndex: Int = 0,
        maxConcurrent: Int = 3,
        cfg: ProviderConfig = config(),
    ) = SubAgentModelResolver.resolve(
        config = cfg,
        mainEntryId = mainEntryId,
        storedSlots = slots,
        maxConcurrent = maxConcurrent,
        requested = requested,
        roundRobinIndex = roundRobinIndex,
    )

    @Test
    fun `no slots means the main session model`() {
        val r = resolve()!!
        assertEquals("m-deepseek", r.entry.id)
        assertTrue("a lane with no slot is pinned to the chat model", r.pinnedToMain)
        assertEquals("no slot owns the depth setting", -1, r.slotIndex)
        assertTrue(r.pinnedSlots.all { it.isBlank() })
    }

    @Test
    fun `cross-provider slots are blanked, never dispatched`() {
        val r = resolve(slots = listOf("m-ocr", "m-kimi"))!!
        assertEquals("m-deepseek", r.entry.id)
        assertTrue(r.pinnedToMain)
        assertEquals("slots stay positional and inert", listOf("", "", ""), r.pinnedSlots)
    }

    @Test
    fun `same-provider slot overrides the model but not the channel`() {
        val r = resolve(slots = listOf("m-glm"))!!
        assertEquals("m-glm", r.entry.id)
        assertEquals("gcmod", r.entry.providerInstanceId)
        assertFalse("an explicit slot is not the main model", r.pinnedToMain)
        assertEquals(0, r.slotIndex)
    }

    @Test
    fun `cross-provider rotation cannot leak on the second lane`() {
        // Slot 1 is same-provider, slot 2 is foreign and must stay inert.
        val slots = listOf("m-glm", "m-ocr")
        assertEquals("slot 0 is same-provider", "m-glm", resolve(slots = slots, roundRobinIndex = 0)!!.entry.id)
        // Lane 1 lands on the blanked slot and falls back to the chat model —
        // never to the foreign provider, and never borrowed from slot 0.
        assertEquals("lane 1 falls back to the chat model", "m-deepseek", resolve(slots = slots, roundRobinIndex = 1)!!.entry.id)
        // The retry path rotates over pinnedSlots, so it sees the same picture.
        val pinned = resolve(slots = slots)!!.pinnedSlots
        assertEquals(listOf("m-glm", "", ""), pinned)
    }

    @Test
    fun `explicit request for another provider is refused`() {
        val r = resolve(requested = "m-ocr")!!
        assertEquals("m-deepseek", r.entry.id)
        assertEquals("gcmod", r.entry.providerInstanceId)
    }

    @Test
    fun `explicit request is honoured only for an assigned slot`() {
        // Candidates are the pinned slots plus the chat model: a same-provider
        // model that is NOT a slot is not silently promoted.
        assertEquals("unassigned", "m-deepseek", resolve(requested = "m-glm")!!.entry.id)
        val r = resolve(slots = listOf("m-glm"), requested = "m-glm")!!
        assertEquals("m-glm", r.entry.id)
        assertEquals("requested slot owns the depth setting", 0, r.slotIndex)
    }

    @Test
    fun `stale slot ids are dropped, positions kept`() {
        val slots = listOf("gone", "m-glm")
        assertEquals("slot 0 is now empty", "m-deepseek", resolve(slots = slots, roundRobinIndex = 0)!!.entry.id)
        assertEquals("slot 1 survives", "m-glm", resolve(slots = slots, roundRobinIndex = 1)!!.entry.id)
    }

    @Test
    fun `without a session model every entry stays eligible`() {
        // Bootstrap window: nothing selected yet. Resolution must still return
        // a model instead of failing closed with "No model available".
        val r = resolve(mainEntryId = null, slots = listOf("m-ocr"))!!
        assertEquals("m-ocr", r.entry.id)
        assertFalse(r.pinnedToMain)
    }

    @Test
    fun `nothing configured yields no resolution`() {
        val empty = ProviderConfig()
        assertNull(resolve(mainEntryId = null, cfg = empty))
    }

    @Test
    fun `slots are resized to the concurrency cap`() {
        val r = resolve(slots = listOf("m-glm", "m-deepseek", "m-glm"), maxConcurrent = 2)!!
        assertEquals(2, r.pinnedSlots.size)
    }

    @Test
    fun `describe names the channel and model for logcat`() {
        val cfg = config()
        val text = SubAgentModelResolver.describe(cfg, cfg.modelEntries.first())
        assertTrue("expected provider and model in: $text", text.contains("GCMOD"))
        assertTrue("expected provider and model in: $text", text.contains("deepseek-v4.1-flash"))
    }
}
