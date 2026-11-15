package com.openminis.app.data

import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-runaway] The ceilings that bound a compaction run.
 *
 * Compaction previously had no time or call ceiling of its own: its only bound
 * was each provider's 10-minute OkHttp readTimeout, and the split-retry path
 * could issue 1+2+4+8 = 15 sequential leaf calls before depth 3 stopped it.
 * Slow-but-not-failing calls therefore accumulated into the ~20-minute apparent
 * hang users reported. These pin the arithmetic of the replacement budgets.
 */
class CompactBudgetTest {

    @Test
    fun `short transcript gets the base timeout`() {
        assertEquals(
            ChatViewModel.COMPACT_TIMEOUT_BASE_MS,
            ChatViewModel.compactTimeoutMsFor(0),
        )
        assertEquals(
            ChatViewModel.COMPACT_TIMEOUT_BASE_MS,
            ChatViewModel.compactTimeoutMsFor(9_999),
        )
    }

    @Test
    fun `timeout grows with transcript length`() {
        // The point of a dynamic budget: a long first compaction must not be
        // cut off by a limit sized for a short one.
        val short = ChatViewModel.compactTimeoutMsFor(5_000)
        val medium = ChatViewModel.compactTimeoutMsFor(50_000)
        val long = ChatViewModel.compactTimeoutMsFor(120_000)
        assertTrue("longer transcript must get more time", medium > short)
        assertTrue("longer transcript must get more time", long > medium)
    }

    @Test
    fun `growth is one step per 10k characters`() {
        val base = ChatViewModel.COMPACT_TIMEOUT_BASE_MS
        val step = ChatViewModel.COMPACT_TIMEOUT_PER_10K_CHARS_MS
        assertEquals(base + step, ChatViewModel.compactTimeoutMsFor(10_000))
        assertEquals(base + 3 * step, ChatViewModel.compactTimeoutMsFor(35_000))
    }

    @Test
    fun `timeout is capped so nothing can hang indefinitely`() {
        val huge = ChatViewModel.compactTimeoutMsFor(10_000_000)
        assertEquals(ChatViewModel.COMPACT_TIMEOUT_MAX_MS, huge)
    }

    @Test
    fun `cap stays below the providers' read timeout`() {
        // Providers use a 10-minute readTimeout. Our ceiling must land first,
        // or the run ends on a socket timeout with no message instead of our
        // own "timed out, try again" path.
        val providerReadTimeoutMs = 10 * 60 * 1000L
        assertTrue(
            "compact cap (${ChatViewModel.COMPACT_TIMEOUT_MAX_MS}ms) must be under " +
                "the provider read timeout (${providerReadTimeoutMs}ms)",
            ChatViewModel.COMPACT_TIMEOUT_MAX_MS < providerReadTimeoutMs,
        )
    }

    @Test
    fun `call budget is well under what the depth cap alone permits`() {
        // depth<3 allows 1+2+4+8 = 15 leaf calls. The budget is what actually
        // bounds wall-clock when each call is slow rather than failing fast.
        val depthCapWorstCase = 1 + 2 + 4 + 8
        assertTrue(
            "call budget must materially cut the depth-cap worst case",
            ChatViewModel.MAX_COMPACT_LLM_CALLS < depthCapWorstCase,
        )
        // Still enough for a full first split (1 + 2) plus a deeper rescue,
        // so the mechanism that exists to save an over-length compaction
        // is not budgeted out of existence.
        assertTrue(
            "budget must still allow a first split plus a rescue",
            ChatViewModel.MAX_COMPACT_LLM_CALLS >= 5,
        )
    }

    @Test
    fun `worst-case wall clock stays under the provider read timeout`() {
        // The regression this change exists to prevent is the open-ended run.
        // The ceiling is now 9 minutes (to cover a 500k-token history split
        // into ~11 leaves), still inside the providers' 10-minute readTimeout.
        val worstMs = ChatViewModel.COMPACT_TIMEOUT_MAX_MS
        assertTrue("must be under 10 minutes", worstMs <= 10 * 60 * 1000L)
        assertTrue("must leave margin under the provider read timeout", worstMs <= 9 * 60 * 1000L)
    }

    @Test
    fun `large history gets a call budget that can finish the job`() {
        // The failure this pins: a 500k-token history at a 48k segment budget
        // needs 11 leaves, but the fixed budget of 6 threw at call 7 and every
        // stage failed. The budget must scale with segments, still bounded.
        val budget = ChatViewModel.compactCallBudgetFor(500_000, 96_000)
        assertTrue("budget must cover all 6 segments", budget >= 6)
        assertTrue(
            "budget must stay under the ceiling",
            budget <= ChatViewModel.MAX_COMPACT_LLM_CALLS_CEILING,
        )
        assertEquals(
            ChatViewModel.MAX_COMPACT_LLM_CALLS,
            ChatViewModel.compactCallBudgetFor(10_000, 96_000),
        )
    }

    @Test
    fun `split depth grows for a large history and stays bounded`() {
        assertEquals(
            ChatViewModel.COMPACT_SPLIT_DEPTH_FLOOR,
            ChatViewModel.compactMaxDepthFor(10_000, 96_000),
        )
        assertTrue(
            "6 segments need at least depth 3 (8 leaves)",
            ChatViewModel.compactMaxDepthFor(500_000, 96_000) >= 3,
        )
        assertEquals(
            ChatViewModel.COMPACT_SPLIT_DEPTH_CEILING,
            ChatViewModel.compactMaxDepthFor(100_000_000, 8_000),
        )
    }

    @Test
    fun `segment budget follows the model window, not a fixed number`() {
        // The budget is a RATIO of the model window. A 200K model gets 150K;
        // a 1M model gets 750K, not the 200K model's slice. Locking a literal
        // number here would make a 1M model compact in 200K-sized pieces.
        assertEquals(
            200_000 * ChatViewModel.COMPACT_SEGMENT_WINDOW_NUMERATOR /
                ChatViewModel.COMPACT_SEGMENT_WINDOW_DENOMINATOR,
            ChatViewModel.compactSegmentTokenBudget(200_000),
        )
        assertEquals(
            1_000_000 * ChatViewModel.COMPACT_SEGMENT_WINDOW_NUMERATOR /
                ChatViewModel.COMPACT_SEGMENT_WINDOW_DENOMINATOR,
            ChatViewModel.compactSegmentTokenBudget(1_000_000),
        )
    }

    @Test
    fun `a 1M model is not capped at a 200K model's slice`() {
        val small = ChatViewModel.compactSegmentTokenBudget(200_000)
        val large = ChatViewModel.compactSegmentTokenBudget(1_000_000)
        assertTrue("1M budget must scale past 200K", large >= small * 3)
        assertTrue(
            "a 130k-token history must fit one call even on 200K",
            130_000 <= small,
        )
    }

    @Test
    fun `leaves run two at a time`() {
        // [T-android-compact-parallel] The halves of a split are independent
        // calls; two in flight halves the wall clock without flooding the
        // local gateway. The per-run call budget still bounds the total.
        assertEquals(2, ChatViewModel.COMPACT_LEAF_CONCURRENCY)
    }

    @Test
    fun `chinese text is not underestimated by four chars per token`() {
        // 400 CJK chars are ≈400 tokens, not 100. Under-counting kept the
        // proactive split from firing on Chinese-heavy sessions.
        val chinese = "安卓会话压缩失败".repeat(50)
        val ascii = "a".repeat(chinese.length)
        val cjkTokens = ChatViewModel.estimateCompactTokens(chinese)
        val asciiTokens = ChatViewModel.estimateCompactTokens(ascii)
        assertTrue("CJK must be counted near 1 token/char", cjkTokens >= chinese.length * 9 / 10)
        assertTrue("ASCII stays at ~4 chars/token", asciiTokens <= ascii.length / 3)
    }
}
