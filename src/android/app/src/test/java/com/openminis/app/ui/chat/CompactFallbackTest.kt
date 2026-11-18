package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-009] Deterministic floor for a compaction whose summary model said nothing. */
private const val H = "LOCAL DIGEST (test)"

class CompactFallbackTest {

    @Test
    fun `empty transcript yields nothing to write`() {
        assertEquals("", CompactFallback.digest("", messageCount = 10, header = H))
        assertEquals("", CompactFallback.digest("   \n\t ", messageCount = 10, header = H))
    }

    @Test
    fun `short transcript is kept whole and labelled`() {
        val out = CompactFallback.digest("user: 把网关修好\nassistant: 已定位掉线原因", messageCount = 7, header = H)
        assertTrue(out.startsWith(H))
        assertTrue("count is visible", out.contains("7"))
        assertTrue("body survives", out.contains("已定位掉线原因"))
        assertFalse("nothing was elided", out.contains("elided"))
    }

    @Test
    fun `long transcript keeps both ends and says what it dropped`() {
        val head = "HEAD-" + "a".repeat(4_000)
        val mid = "MID-" + "m".repeat(20_000)
        val tail = "b".repeat(4_000) + "-TAIL"
        val out = CompactFallback.digest(head + mid + tail, messageCount = 42, header = H, budgetChars = 1_200)
        assertTrue("opening survives", out.contains("HEAD-"))
        assertTrue("latest material survives", out.contains("-TAIL"))
        assertFalse("the middle is what gets dropped", out.contains("MID-"))
        assertTrue("dropped volume is reported", out.contains("elided"))
        assertTrue("stays near the budget", out.length < 1_600)
    }

    @Test
    fun `digest is deterministic`() {
        val text = "x".repeat(5_000)
        assertEquals(
            CompactFallback.digest(text, 3, H, 400),
            CompactFallback.digest(text, 3, H, 400),
        )
    }

    @Test
    fun `usable rejects a digest that carries no information`() {
        assertFalse(CompactFallback.usable(""))
        assertFalse(CompactFallback.usable("too short"))
        assertTrue(CompactFallback.usable("z".repeat(CompactFallback.MIN_USEFUL_CHARS)))
    }

    @Test
    fun `failure set is closed`() {
        assertEquals(
            listOf("Timeout", "EmptyOutput", "Cancelled", "Error"),
            CompactFallback.Failure.entries.map { it.name },
        )
    }
}
